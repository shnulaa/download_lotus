package com.example.downloader.core;

import com.example.downloader.entity.DownloadRecord;
import com.example.downloader.model.ChunkInfo;
import com.example.downloader.repo.DownloadRepository;
import com.example.downloader.util.FileUtils;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片管理器
 * <p>
 * 负责分片的创建、恢复、保存和重分配逻辑
 * </p>
 */
@Slf4j
public class ChunkManager {
    private final DownloadRecord record;
    private final DownloadRepository repository;
    private final Map<String, ChunkInfo> chunkMap = new ConcurrentHashMap<>();
    private final HttpClientFactory httpClientFactory;

    public ChunkManager(DownloadRecord record, DownloadRepository repository, HttpClientFactory httpClientFactory) {
        this.record = record;
        this.repository = repository;
        this.httpClientFactory = httpClientFactory;
    }

    public Map<String, ChunkInfo> getChunkMap() {
        return chunkMap;
    }

    /**
     * 预处理：处理文件名、大小、重定向
     */
    public void prepare() throws IOException {
        try (CloseableHttpClient client = httpClientFactory.createHttpClient(record)) {
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
                record.setSupportRange(false); // 未知大小强制不支持 Range
            }
        }
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
            record.setSupportRange(true);
        }
    }

    /**
     * 创建单流分片（用于不支持Range的下载）
     */
    public void createSingleStreamChunk() {
        // 只有1个线程，颜色索引为0
        ChunkInfo chunk = new ChunkInfo("STREAM", 0, -1, 0, 0);
        chunkMap.put(chunk.getId(), chunk);
    }

    /**
     * 分片文件
     */
    public void splitChunks(int count) {
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

    /**
     * 保存分片信息到数据库
     */
    public void saveChunks() {
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

    /**
     * 从数据库恢复分片信息
     */
    public void restoreChunks() {
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

    /**
     * 动态重分配逻辑
     */
    public void tryRebalance() {
        // 这里可以添加动态重分配逻辑
        // 需要根据 colorIndex 传递给新分片
    }

    /**
     * 执行分片拆分
     */
    public synchronized void performSplit(ChunkInfo parent) {
        long mid = parent.getCurrent().get() + (parent.getEnd() - parent.getCurrent().get()) / 2;
        long oldEnd = parent.getEnd();

        parent.setEnd(mid);
        // 新分片赋予新的 ColorIndex (简单累加)
        ChunkInfo newChunk = new ChunkInfo(UUID.randomUUID().toString(), mid + 1, oldEnd, mid + 1,
                parent.getColorIndex() + 1);

        chunkMap.put(newChunk.getId(), newChunk);
    }
}