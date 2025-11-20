package com.example.downloader.core;

import com.example.downloader.entity.DownloadRecord;
import com.example.downloader.model.ChunkInfo;
import com.example.downloader.model.DownloadStatus;
import com.example.downloader.repo.DownloadRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private long globalSpeed = 0; // 全局速度

    private ForkJoinPool chunkExecutor;
    private final Map<String, AtomicBoolean> activeWorkers = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    private final ChunkManager chunkManager;
    private final HttpClientFactory httpClientFactory;

    public DownloadTaskContext(DownloadRecord record, DownloadRepository repo, int threads) {
        this.record = record;
        this.repository = repo;
        this.chunkExecutor = new ForkJoinPool(Math.min(threads + 1, 32));
        this.httpClientFactory = new HttpClientFactory();
        this.chunkManager = new ChunkManager(record, repository, httpClientFactory);
        // 恢复chunks信息
        this.chunkManager.restoreChunks();
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

    public Map<String, ChunkInfo> getChunkMap() {
        return chunkManager.getChunkMap();
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
                    int threads = chunkManager.getChunkMap().isEmpty() ? 8 : chunkManager.getChunkMap().size();
                    chunkExecutor = new ForkJoinPool(Math.min(threads + 1, 32));
                    log.info("从暂停状态恢复，重新创建线程池，并发数: {}", threads);
                }

                // 只有IDLE状态才需要prepare
                if (status == DownloadStatus.IDLE) {
                    chunkManager.prepare();
                    saveRecord(); // 保存入库
                } else if (status == DownloadStatus.PAUSED) {
                    // PAUSED状态恢复，不需要prepare
                    log.info("从暂停状态恢复任务 {}, chunks数量: {}", record.getId(), chunkManager.getChunkMap().size());
                    // 确保从数据库重新加载最新的chunk进度
                    if (chunkManager.getChunkMap().isEmpty()) {
                        chunkManager.restoreChunks();
                        log.info("从数据库恢复了 {} 个chunk", chunkManager.getChunkMap().size());
                    }
                }

                status = DownloadStatus.DOWNLOADING;
                updateStatusInDb("DOWNLOADING");

                if (chunkManager.getChunkMap().isEmpty()) {
                    // 这种情况下是初始化分片
                    boolean supportRange = record.getSupportRange() != null ? record.getSupportRange() : true;
                    if (supportRange && record.getTotalSize() > 0) {
                        log.info("任务 {} 支持Range，分片数: {}", record.getId(), chunkExecutor.getParallelism());
                        chunkManager.splitChunks(chunkExecutor.getParallelism());
                    } else {
                        // 针对 GitHub 这种无法获取长度或不支持 Range 的
                        log.info("任务 {} 不支持Range，使用流式下载", record.getId());
                        chunkManager.createSingleStreamChunk();
                    }
                }

                // 提交任务
                chunkManager.getChunkMap().values().stream().filter(c -> !c.isFinished()).forEach(this::submitTask);
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
            activeWorkers.values().forEach(running -> running.set(false));

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
            chunkManager.saveChunks();
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
        activeWorkers.values().forEach(running -> running.set(false));
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

    private void submitTask(ChunkInfo chunkInfo) {
        AtomicBoolean running = new AtomicBoolean(true);
        ChunkWorker worker = new ChunkWorker(chunkInfo, record, running, fileLock, httpClientFactory);
        activeWorkers.put(chunkInfo.getId(), running);
        chunkExecutor.execute(worker);
    }

    // 监控线程改进：聚合全局速度
    private void startMonitor() {
        new Thread(() -> {
            while (status == DownloadStatus.DOWNLOADING) {
                try {
                    Thread.sleep(1000);
                    long sumSpeed = 0;
                    boolean allFinished = true;

                    for (ChunkInfo chunk : chunkManager.getChunkMap().values()) {
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

                    if (allFinished && !chunkManager.getChunkMap().isEmpty()) {
                        log.info("任务 {} 所有分片下载完成！文件: {}", record.getId(), record.getFileName());
                        status = DownloadStatus.FINISHED;
                        updateStatusInDb("FINISHED");
                        saveRecord(); // 保存chunks信息以记录线程颜色
                        break;
                    }

                    // 仅在支持 Range 且大小已知时尝试重分配
                    boolean supportRange = record.getSupportRange() != null ? record.getSupportRange() : true;
                    if (supportRange && record.getTotalSize() > 0) {
                        tryRebalance();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void saveRecord() {
        // 保存chunks信息
        chunkManager.saveChunks();
        repository.save(record);
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
        AtomicBoolean running = activeWorkers.get(parent.getId());
        if (running != null)
            running.set(false);

        chunkManager.performSplit(parent);
        submitTask(parent); // 重新提交前半段
        // 提交后半段
        ChunkInfo newChunk = chunkManager.getChunkMap().values().stream()
                .filter(c -> c.getStart() == parent.getEnd() + 1)
                .findFirst()
                .orElse(null);
        if (newChunk != null) {
            submitTask(newChunk);
        }
    }
}
