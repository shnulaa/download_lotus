package com.example.downloader.service;

import com.example.downloader.core.DownloadTaskContext;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载任务服务
 * <p>
 * 管理多个下载任务，支持离线下载（内存持久化，重启后丢失）。
 * 如需完全持久化，可扩展为连接数据库。
 * </p>
 */
@Service
public class DownloadService {

    // 内存任务库，重启后丢失（如需完全离线持久化，此处应连接数据库）
    private final Map<String, DownloadTaskContext> taskStore = new ConcurrentHashMap<>();

    /**
     * 创建新的下载任务
     *
     * @param url 下载URL
     * @param path 保存路径
     * @param threads 线程数
     * @return 下载任务上下文
     * @deprecated This service is deprecated in V1.0.1. Use DownloadController directly.
     */
    @Deprecated
    public DownloadTaskContext createTask(String url, String path, int threads) {
        // This method is deprecated as we now use JPA entities
        throw new UnsupportedOperationException("Use DownloadController with JPA entities instead");
    }

    /**
     * 根据任务ID获取任务
     *
     * @param taskId 任务ID
     * @return 下载任务上下文，不存在返回null
     */
    public DownloadTaskContext getTask(String taskId) {
        return taskStore.get(taskId);
    }

    /**
     * 获取所有任务
     *
     * @return 所有任务的集合
     */
    public Collection<DownloadTaskContext> getAllTasks() {
        return taskStore.values();
    }

    /**
     * 移除已完成的任务（可选，用于清理内存）
     *
     * @param taskId 任务ID
     * @deprecated This service is deprecated in V1.0.1. Use DownloadController directly.
     */
    @Deprecated
    public void removeTask(String taskId) {
        // Deprecated method
        throw new UnsupportedOperationException("Use DownloadController with JPA entities instead");
    }
}
