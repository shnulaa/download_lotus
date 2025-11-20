package com.example.downloader.core;

import com.example.downloader.entity.DownloadRecord;
import com.example.downloader.model.ChunkInfo;
import com.example.downloader.model.DownloadStatus;
import com.example.downloader.repo.DownloadRepository;
import com.example.downloader.util.FileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.alibaba.fastjson.JSON;

/**
 * 下载任务上下文
 * <p>
 * 负责管理单个文件的所有分片任务、状态流转、断点续传和动态重分配。
 * 支持智能文件名处理和 Range 检测降级。
 * </p>
 */
@Slf4j
@Data
public class DownloadTaskContext {

    private DownloadRecord record;
    private DownloadRepository repository;

    private volatile DownloadStatus status = DownloadStatus.IDLE;
    private boolean supportRange = true;
    private long globalSpeed = 0; // 全局速度

    private ForkJoinPool chunkExecutor;
    private final Map<String, ChunkInfo> chunkMap = new ConcurrentHashMap<>();
    private final Map<String, ChunkWorker> activeWorkers = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    public DownloadTaskContext(DownloadRecord record, DownloadRepository repo, int threads) {
        this.record = record;
        this.repository = repo;
        this.chunkExecutor = new ForkJoinPool(Math.min(threads + 1, 32));
        this.supportRange = record.getSupportRange() != null ? record.getSupportRange() : true;
        // 恢复chunks信息
        restoreChunks();
    }

    // Legacy getter methods for compatibility
    public String getTaskId() {
        return record.getId();
    }

    public String getFileName() {
        return record.getFileName();
    }

    public String getDownloadUrl() {
        return record.getUrl();
    }

    public long getTotalSize() {
        return record.getTotalSize();
    }

    public int getThreadCount() {
        return chunkExecutor.getParallelism() - 1;
    }

    public DownloadRecord getRecord() {
        return record;
    }

    public void start() {
        if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.FINISHED) {
            log.warn("任务 {} 已在运行或已完成，跳过启动", record.getId());
            return;
        }

        log.info("开始启动下载任务：{}, URL: {}, 当前状态: {}", record.getId(), record.getUrl(), status);
        // 异步启动防止阻塞Controller
        new Thread(() -> {
            try {
                // 如果是从暂停恢复，需要重新创建线程池
                if (status == DownloadStatus.PAUSED && (chunkExecutor == null || chunkExecutor.isShutdown())) {
                    int threads = chunkMap.isEmpty() ? 8 : chunkMap.size();
                    chunkExecutor = new ForkJoinPool(Math.min(threads + 1, 32));
                    log.info("从暂停状态恢复，重新创建线程池，并发数: {}", threads);
                }

                // 只有IDLE状态才需要prepare
                if (status == DownloadStatus.IDLE) {
                    prepare();
                    saveRecord(); // 保存入库
                } else if (status == DownloadStatus.PAUSED) {
                    // PAUSED状态恢复，不需要prepare
                    log.info("从暂停状态恢复任务 {}, chunks数量: {}", record.getId(), chunkMap.size());
                    // 确保从数据库重新加载最新的chunk进度
                    if (chunkMap.isEmpty()) {
                        restoreChunks();
                        log.info("从数据库恢复了 {} 个chunk", chunkMap.size());
                    }
                }

                status = DownloadStatus.DOWNLOADING;
                updateStatusInDb("DOWNLOADING");

                if (chunkMap.isEmpty()) {
                    // 这种情况下是初始化分片
                    if (supportRange && record.getTotalSize() > 0) {
                        log.info("任务 {} 支持Range，分片数: {}", record.getId(), chunkExecutor.getParallelism());
                        splitChunks(chunkExecutor.getParallelism());
                    } else {
                        // 针对 GitHub 这种无法获取长度或不支持 Range 的
                        log.info("任务 {} 不支持Range，使用流式下载", record.getId());
                        createSingleStreamChunk();
                    }
                }

                // 提交任务
                chunkMap.values().stream().filter(c -> !c.isFinished()).forEach(this::submitTask);
                startMonitor();

            } catch (Exception e) {
                log.error("Task start failed", e);
                status = DownloadStatus.ERROR;
                updateStatusInDb("ERROR");
            }
        }).start();
    }

    public void pause() {
        if (status == DownloadStatus.DOWNLOADING) {
            log.info("暂停下载任务: {}", record.getId());
            status = DownloadStatus.PAUSED;

            // 停止所有worker
            activeWorkers.values().forEach(ChunkWorker::stopWork);

            // 等待所有worker完全停止（最多等待3秒）
            long waitStart = System.currentTimeMillis();
            while (!activeWorkers.isEmpty() && (System.currentTimeMillis() - waitStart) < 3000) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.warn("等待worker停止时被中断", e);
                    break;
                }
            }

            activeWorkers.clear();

            // 保存当前进度到数据库
            saveChunks();
            updateStatusInDb("PAUSED");

            // 关闭线程池以完全停止下载
            if (chunkExecutor != null && !chunkExecutor.isShutdown()) {
                chunkExecutor.shutdown();
                log.info("任务 {} 线程池已关闭", record.getId());
            }

            log.info("任务 {} 已暂停，进度已保存", record.getId());
        }
    }

    public void cancel() {
        log.info("取消下载任务: {}", record.getId());
        status = DownloadStatus.CANCELED;
        activeWorkers.values().forEach(ChunkWorker::stopWork);
        activeWorkers.clear();
        // 尝试删除文件
        if (record.getFileName() != null) {
            File file = new File(record.getSavePath(), record.getFileName());
            if (file.exists() && file.delete()) {
                log.info("任务 {} 已删除临时文件: {}", record.getId(), record.getFileName());
            }
        }
        updateStatusInDb("CANCELED");
    }

    private void parseResponseHeaders(CloseableHttpResponse response) {
        Header lenHeader = response.getFirstHeader("Content-Length");
        Header rangeHeader = response.getFirstHeader("Accept-Ranges");

        if (lenHeader != null) {
            try {
                long len = Long.parseLong(lenHeader.getValue());
                // 如果 Range 0-0 返回 Content-Range: bytes 0-0/12345，则总长是 12345
                Header contentRange = response.getFirstHeader("Content-Range");
                if (contentRange != null) {
                    String val = contentRange.getValue();
                    int slash = val.lastIndexOf('/');
                    if (slash > 0)
                        len = Long.parseLong(val.substring(slash + 1));
                }
                record.setTotalSize(len);
            } catch (NumberFormatException ignored) {
            }
        }

        if (rangeHeader != null && "bytes".equalsIgnoreCase(rangeHeader.getValue())) {
            this.supportRange = true;
        }
    }

    private void createSingleStreamChunk() {
        // 只有1个线程，颜色索引为0
        ChunkInfo chunk = new ChunkInfo("STREAM", 0, -1, 0, 0);
        chunkMap.put(chunk.getId(), chunk);
    }

    private void splitChunks(int count) {
        long total = record.getTotalSize();
        long blockSize = total / count;
        for (int i = 0; i < count; i++) {
            long start = i * blockSize;
            long end = (i == count - 1) ? total - 1 : (i + 1) * blockSize - 1;
            // 分配不同的 colorIndex (i % 32) 用于前端着色
            ChunkInfo chunk = new ChunkInfo(UUID.randomUUID().toString(), start, end, start, i);
            chunkMap.put(chunk.getId(), chunk);
        }
    }

    private void submitTask(ChunkInfo chunkInfo) {
        ChunkWorker worker = new ChunkWorker(chunkInfo);
        activeWorkers.put(chunkInfo.getId(), worker);
        chunkExecutor.execute(worker);
    }

    /**
     * 预处理：处理文件名、大小、重定向
     */
    private void prepare() throws IOException {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10000) // 连接超时
                .setSocketTimeout(30000) // 读取超时
                .setRedirectsEnabled(true)// 允许重定向 (解决 GitHub 问题)
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            // 1. 尝试 HEAD
            HttpHead head = new HttpHead(record.getUrl());
            try (CloseableHttpResponse response = client.execute(head)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    parseResponseHeaders(response);
                }
            } catch (Exception e) {
                // HEAD 失败尝试 GET (部分服务器禁用了 HEAD)
            }

            // 2. 如果 HEAD 没拿到大小，尝试 GET (Range: 0-0) 探测
            if (record.getTotalSize() == null || record.getTotalSize() <= 0) {
                HttpGet get = new HttpGet(record.getUrl());
                get.addHeader("Range", "bytes=0-0");
                try (CloseableHttpResponse response = client.execute(get)) {
                    parseResponseHeaders(response);
                }
            }

            // 3. 确定文件名
            if (record.getFileName() == null) {
                String name = FileUtils.getUniqueFileName(record.getSavePath(), record.getUrl());
                record.setFileName(name);
            }

            // 4. 占位 (只有已知大小才占位)
            if (record.getTotalSize() != null && record.getTotalSize() > 0) {
                File file = new File(record.getSavePath(), record.getFileName());
                if (!file.getParentFile().exists())
                    file.getParentFile().mkdirs();
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.setLength(record.getTotalSize());
                }
            } else {
                // 未知大小时，标记为 -1
                if (record.getTotalSize() == null)
                    record.setTotalSize(-1L);
                this.supportRange = false; // 未知大小强制不支持 Range
            }
            record.setSupportRange(this.supportRange);
        }
    }

    // 监控线程改进：聚合全局速度
    private void startMonitor() {
        new Thread(() -> {
            while (status == DownloadStatus.DOWNLOADING) {
                try {
                    Thread.sleep(1000);
                    long sumSpeed = 0;
                    boolean allFinished = true;

                    for (ChunkInfo chunk : chunkMap.values()) {
                        if (!chunk.isFinished()) {
                            allFinished = false;
                            long curr = chunk.getCurrent().get();
                            long sp = curr - chunk.getLastRecordBytes();
                            // 修复负数bug (极少数情况)
                            if (sp < 0)
                                sp = 0;
                            chunk.setSpeed(sp);
                            chunk.setLastRecordBytes(curr);
                            sumSpeed += sp;
                        } else {
                            chunk.setSpeed(0);
                        }
                    }

                    this.globalSpeed = sumSpeed;

                    if (allFinished && !chunkMap.isEmpty()) {
                        log.info("任务 {} 所有分片下载完成！文件: {}", record.getId(), record.getFileName());
                        status = DownloadStatus.FINISHED;
                        updateStatusInDb("FINISHED");
                        saveRecord(); // 保存chunks信息以记录线程颜色
                        break;
                    }

                    // 仅在支持 Range 且大小已知时尝试重分配
                    if (supportRange && record.getTotalSize() > 0) {
                        tryRebalance();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * 内部类：ForkJoin Worker (实际下载执行者)
     */
    private class ChunkWorker extends RecursiveAction {
        private final ChunkInfo chunkInfo;
        private final AtomicBoolean running = new AtomicBoolean(true);

        public ChunkWorker(ChunkInfo chunkInfo) {
            this.chunkInfo = chunkInfo;
        }

        public void stopWork() {
            running.set(false);
        }

        @Override
        protected void compute() {
            // 错误重试循环（最多5次）
            while (running.get() && !chunkInfo.isFinished() && chunkInfo.getErrorCount() < 5) {
                try {
                    download();
                } catch (Exception e) {
                    chunkInfo.setErrorCount(chunkInfo.getErrorCount() + 1);
                    log.warn("任务 [{}] Chunk {} 下载出错, 重试 {}/5",
                            record.getId(), chunkInfo.getId(), chunkInfo.getErrorCount());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // 如果错误太多
            if (chunkInfo.getErrorCount() >= 5) {
                log.error("任务 [{}] Chunk {} 失败次数过多，已停止", record.getId(), chunkInfo.getId());
                DownloadTaskContext.this.status = DownloadStatus.ERROR;
            }
        }

        private void download() throws IOException {
            CloseableHttpClient client = HttpClients.createDefault();
            HttpGet request = new HttpGet(record.getUrl());

            long startPos = chunkInfo.getCurrent().get();
            // 如果是普通 Range 下载
            if (supportRange && record.getTotalSize() > 0) {
                request.addHeader("Range", "bytes=" + startPos + "-" + chunkInfo.getEnd());
            }
            // 如果是不支持 Range 的流式下载，不加 Range 头，直接读

            File targetFile = new File(record.getSavePath(), record.getFileName());

            try (CloseableHttpResponse response = client.execute(request);
                    InputStream is = response.getEntity().getContent();
                    RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {

                if (supportRange && record.getTotalSize() > 0) {
                    raf.seek(startPos);
                } else {
                    // 流式追加模式：如果是刚开始，seek 0；否则（断网重连）其实是不支持的
                    // 这里简化：流式下载每次都覆盖
                    raf.seek(0);
                    chunkInfo.getCurrent().set(0);
                }

                byte[] buf = new byte[16384]; // 16KB buffer
                int len;
                while (running.get() && (len = is.read(buf)) != -1) {
                    synchronized (fileLock) {
                        if (supportRange && record.getTotalSize() > 0) {
                            raf.seek(chunkInfo.getCurrent().get());
                        } else {
                            // 简单的追加写入位置维护
                            raf.seek(chunkInfo.getCurrent().get());
                        }
                        raf.write(buf, 0, len);
                    }
                    chunkInfo.getCurrent().addAndGet(len);
                }

                // 判定完成
                if (supportRange && record.getTotalSize() > 0) {
                    if (chunkInfo.getCurrent().get() >= chunkInfo.getEnd())
                        chunkInfo.setFinished(true);
                } else {
                    // 流读完就是完成
                    chunkInfo.setFinished(true);
                    // 更新总大小和状态
                    record.setTotalSize(chunkInfo.getCurrent().get());
                    status = DownloadStatus.FINISHED;
                    updateStatusInDb("FINISHED");
                    saveRecord();
                }
            }
        }
    }

    private void saveRecord() {
        // 保存chunks信息
        saveChunks();
        repository.save(record);
    }

    private void saveChunks() {
        if (!chunkMap.isEmpty()) {
            // 将chunkMap转换为JSON字符串保存
            List<Map<String, Object>> chunkList = new ArrayList<>();
            for (ChunkInfo chunk : chunkMap.values()) {
                Map<String, Object> chunkData = new HashMap<>();
                chunkData.put("id", chunk.getId());
                chunkData.put("start", chunk.getStart());
                chunkData.put("end", chunk.getEnd());
                chunkData.put("current", chunk.getCurrentPos());
                chunkData.put("colorIndex", chunk.getColorIndex());
                chunkData.put("finished", chunk.isFinished());
                chunkList.add(chunkData);
            }
            String jsonStr = JSON.toJSONString(chunkList);
            record.setChunksJson(jsonStr);
            log.info("保存 {} 个chunks，JSON长度: {}", chunkList.size(), jsonStr.length());
        }
    }

    private void restoreChunks() {
        String chunksJson = record.getChunksJson();
        if (chunksJson != null && !chunksJson.isEmpty()) {
            try {
                List<Map<String, Object>> chunkList = JSON.parseObject(chunksJson, List.class);
                for (Map<String, Object> chunkData : chunkList) {
                    String id = (String) chunkData.get("id");
                    long start = ((Number) chunkData.get("start")).longValue();
                    long end = ((Number) chunkData.get("end")).longValue();
                    long current = ((Number) chunkData.get("current")).longValue();
                    int colorIndex = ((Number) chunkData.get("colorIndex")).intValue();
                    boolean finished = (Boolean) chunkData.get("finished");

                    ChunkInfo chunk = new ChunkInfo(id, start, end, current, colorIndex);
                    chunk.setFinished(finished);
                    chunkMap.put(id, chunk);
                }
                log.info("恢复 {} 个chunk信息", chunkMap.size());
            } catch (Exception e) {
                log.error("恢复chunk信息失败", e);
            }
        }
    }

    private void updateStatusInDb(String status) {
        record.setStatus(status);
        repository.save(record);
    }

    // 动态重分配逻辑略... 需要根据 colorIndex 传递给新分片
    public void tryRebalance() {
        // ... 省略判断逻辑 ...
        // performSplit(slowChunk);
    }

    private synchronized void performSplit(ChunkInfo parent) {
        // 停止旧的
        ChunkWorker worker = activeWorkers.get(parent.getId());
        if (worker != null)
            worker.running.set(false);

        long mid = parent.getCurrent().get() + (parent.getEnd() - parent.getCurrent().get()) / 2;
        long oldEnd = parent.getEnd();

        parent.setEnd(mid);
        // 新分片赋予新的 ColorIndex (简单累加)
        ChunkInfo newChunk = new ChunkInfo(UUID.randomUUID().toString(), mid + 1, oldEnd, mid + 1,
                parent.getColorIndex() + 1);

        chunkMap.put(newChunk.getId(), newChunk);
        submitTask(parent); // 重新提交前半段
        submitTask(newChunk); // 提交后半段
    }
}
