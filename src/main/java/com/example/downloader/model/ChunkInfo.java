package com.example.downloader.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分片任务信息
 * <p>
 * 记录每个线程负责的下载范围、当前进度和速度
 * </p>
 */
@Data
public class ChunkInfo {
    private String id; // 分片唯一ID
    private long start; // 起始字节位置
    private long end; // 结束字节位置
    @JsonIgnore
    private AtomicLong current; // 当前已下载位置(绝对位置)
    private volatile long speed;// 当前速度 (bytes/s)
    private volatile int errorCount; // 错误次数
    private volatile boolean finished; // 是否完成

    // V1.0.1 新增: 线程颜色索引 (0-31) 用于前端着色
    private int colorIndex;

    // 用于计算速度的临时变量
    private transient long lastRecordBytes;

    public ChunkInfo(String id, long start, long end, long current, int colorIndex) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.current = new AtomicLong(current);
        this.lastRecordBytes = current;
        this.finished = false;
        this.errorCount = 0;
        this.colorIndex = colorIndex;
    }

    public long getCurrentPos() {
        return current.get();
    }

}
