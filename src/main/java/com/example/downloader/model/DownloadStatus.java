package com.example.downloader.model;

/**
 * 下载状态枚举
 * <p>
 * 定义下载任务的各种状态
 * </p>
 */
public enum DownloadStatus {
    IDLE,       // 空闲
    PREPARING,  // 准备中（连接文件）
    DOWNLOADING,// 下载中
    PAUSED,     // 暂停
    CANCELED,   // 取消
    FINISHED,   // 完成
    ERROR;      // 错误

    /**
     * 判断是否为终止状态（不可恢复的状态）
     *
     * @return 如果是 FINISHED, CANCELED 或 ERROR 返回 true
     */
    public boolean isTerminal() {
        return this == FINISHED || this == CANCELED || this == ERROR;
    }
}

