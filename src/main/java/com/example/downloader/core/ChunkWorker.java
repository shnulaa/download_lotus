package com.example.downloader.core;

import com.example.downloader.entity.DownloadRecord;
import com.example.downloader.model.ChunkInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.*;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分片下载工作器
 * <p>
 * 负责单个分片的下载执行，支持错误重试和进度跟踪
 * </p>
 */
@Slf4j
public class ChunkWorker extends RecursiveAction {
    private final ChunkInfo chunkInfo;
    private final DownloadRecord record;
    private final AtomicBoolean running;
    private final Object fileLock;
    private final HttpClientFactory httpClientFactory;

    public ChunkWorker(ChunkInfo chunkInfo, DownloadRecord record, AtomicBoolean running,
                      Object fileLock, HttpClientFactory httpClientFactory) {
        this.chunkInfo = chunkInfo;
        this.record = record;
        this.running = running;
        this.fileLock = fileLock;
        this.httpClientFactory = httpClientFactory;
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
        }
    }

    private void download() throws IOException {
        CloseableHttpClient client = httpClientFactory.createHttpClient(record);
        HttpGet request = new HttpGet(record.getUrl());

        long startPos = chunkInfo.getCurrent().get();
        boolean supportRange = record.getSupportRange() != null ? record.getSupportRange() : true;

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
            }
        }
    }
}