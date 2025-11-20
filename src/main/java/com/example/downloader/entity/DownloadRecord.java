package com.example.downloader.entity;

import lombok.Data;
import javax.persistence.*;
import java.util.Date;

@Entity
@Data
@Table(name = "download_records")
public class DownloadRecord {
    @Id
    private String id; // UUID

    @Column(length = 2048, nullable = false)
    private String url;
    private String savePath;
    private String fileName;

    private Long totalSize; // -1 表示未知
    private String status; // IDLE, DOWNLOADING, FINISHED, ERROR, etc.

    private Date createdTime;

    @Column(columnDefinition = "boolean default true")
    private Boolean supportRange = true;

    @Column(length = 10000)
    private String chunksJson; // 存储chunk信息的JSON字符串

    // 代理设置
    private String proxyType; // "HTTP", "SOCKS", or null
    private String proxyHost;
    private Integer proxyPort;

    // 用于UI展示的简单字段
    @Transient
    private long downloadedSize;
    @Transient
    private long speed;

    // Explicit getters and setters for compilation issues
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSavePath() {
        return savePath;
    }

    public void setSavePath(String savePath) {
        this.savePath = savePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(Long totalSize) {
        this.totalSize = totalSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    public long getDownloadedSize() {
        return downloadedSize;
    }

    public void setDownloadedSize(long downloadedSize) {
        this.downloadedSize = downloadedSize;
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public Boolean getSupportRange() {
        return supportRange;
    }

    public void setSupportRange(Boolean supportRange) {
        this.supportRange = supportRange;
    }

    public String getChunksJson() {
        return chunksJson;
    }

    public void setChunksJson(String chunksJson) {
        this.chunksJson = chunksJson;
    }

    public String getProxyType() {
        return proxyType;
    }

    public void setProxyType(String proxyType) {
        this.proxyType = proxyType;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }
}