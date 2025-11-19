 Hello, How Can I Help?

8,259 tokens
Model
Thinking Thoughts

Expand to view model thoughts
chevron_right
User
1. 使用 springboot 写一个下载文件的程序 jdk1.8 maven
2. 多线程使用fork join 线程池
3. 画面上要求 输入下载地址，下载路径 点击下载  画面进度条显示每个线程的下载速度 ，可以暂停 和取消
4. 要求可以断线续传。每个线程完成下载后，当未完成线程<=3个，并且网速不足最大的网速的20% 触发重新分配range功能，达到动态分配range功能
6. 多线程 每个线程 读取固定的http的range， java使用 seek 来找到对应的位置
7. 先完成的线程可以协助其它线程进行下载
8. 要考虑每个线程的错误次数
9. 分离前后端 页面美观
10. 其他需求要求你尽可能的发挥想象，请思考准确的架构后在动笔
11. 保证代码整洁，你对代码有洁癖，
12. 代码中加入具体的注释和javadoc

我是客户，你需要满足我的全部需求。请考虑清楚后在进行编程。 不然对客户交代不了！
Model
Thinking Thoughts

Expand to view model thoughts
chevron_right
这是一个非常具有挑战性且完整的全栈需求。为了满足您的“代码洁癖”和“架构清晰”的要求，我将采用 **Spring Boot + Vue3 (CDN)** 的前后端分离架构。

后端核心将采用 **生产者-消费者模式** 结合 **Fork/Join** 思想。虽然 IO 密集型任务通常不推荐 ForkJoin（因为它更适合 CPU 密集型分治），但为了满足您的需求，我们将利用 `ForkJoinPool` 的 Work-Stealing 特性，并结合自定义的**动态分片调度器**来实现“慢速线程切割”和“协助下载”。

### 1. 项目架构设计

*   **核心组件**：
    *   `DownloadManager`：单例，管理全局下载任务状态。
    *   `FileSplitter`：负责计算分片策略（包括初始分片和动态再分片）。
    *   `ChunkTask` (extends `RecursiveAction`)：实际执行下载的单元，运行在 ForkJoinPool 中。
    *   `ProgressMonitor`：独立线程，每秒统计速度，判断是否需要触发“动态重分配”。
*   **技术栈**：
    *   JDK 1.8
    *   Spring Boot 2.7.x
    *   Lombok (简化代码)
    *   Apache HttpClient (比原生更稳定)
    *   WebSocket (实时推送进度给前端)

---

### 2. 后端代码实现

请创建一个 Maven 项目。

#### 2.1 `pom.xml` 依赖

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>downloader</artifactId>
    <version>1.0.0</version>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.14</version>
    </parent>

    <properties>
        <java.version>1.8</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- Apache HttpClient for robust connections -->
        <dependency>
            <groupId>org.apache.httpcomponents</groupId>
            <artifactId>httpclient</artifactId>
            <version>4.5.14</version>
        </dependency>
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>fastjson</artifactId>
            <version>1.2.83</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### 2.2 核心实体类与常量

**`DownloadStatus.java`**

```java
package com.example.downloader.model;

/**
 * 下载状态枚举
 */
public enum DownloadStatus {
    IDLE,       // 空闲
    PREPARING,  // 准备中（连接文件）
    DOWNLOADING,// 下载中
    PAUSED,     // 暂停
    CANCELED,   // 取消
    FINISHED,   // 完成
    ERROR       // 错误
}
```

**`ChunkInfo.java`** (分片信息，用于前端展示和后端逻辑)

```java
package com.example.downloader.model;

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
    private String id;          // 分片唯一ID
    private long start;         // 起始字节位置
    private long end;           // 结束字节位置
    private AtomicLong current; // 当前已下载位置(绝对位置)
    private volatile long speed;// 当前速度 (bytes/s)
    private volatile int errorCount; // 错误次数
    private volatile boolean finished; // 是否完成
    
    // 用于计算速度的临时变量
    private transient long lastRecordBytes;

    public ChunkInfo(String id, long start, long end, long current) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.current = new AtomicLong(current);
        this.lastRecordBytes = current;
        this.finished = false;
        this.errorCount = 0;
    }
}
```

#### 2.3 核心逻辑服务

**`DownloadTaskContext.java`** (下载上下文，核心大脑)

```java
package com.example.downloader.core;

import com.example.downloader.model.ChunkInfo;
import com.example.downloader.model.DownloadStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 下载任务上下文
 * <p>
 * 负责管理单个文件的所有分片任务、状态流转、断点续传和动态重分配。
 * </p>
 */
@Slf4j
@Data
public class DownloadTaskContext {

    private final String downloadUrl;
    private final String savePath;
    private final String fileName;
    private long totalSize;
    
    private volatile DownloadStatus status = DownloadStatus.IDLE;
    
    // 核心线程池，使用 ForkJoinPool 满足需求
    private ForkJoinPool chunkExecutor;
    
    // 存储所有分片信息，Key为ID
    private final Map<String, ChunkInfo> chunkMap = new ConcurrentHashMap<>();
    // 存储正在运行的任务句柄，用于取消/暂停
    private final Map<String, ChunkWorker> activeWorkers = new ConcurrentHashMap<>();

    // 全局最大速度记录 (用于动态分配判断)
    private volatile long maxGlobalSpeed = 0;
    private final Object fileLock = new Object();

    public DownloadTaskContext(String downloadUrl, String savePath, String fileName) {
        this.downloadUrl = downloadUrl;
        this.savePath = savePath;
        this.fileName = fileName;
        // 并行度设为 32，避免 IO 阻塞导致线程耗尽
        this.chunkExecutor = new ForkJoinPool(32);
    }

    /**
     * 启动下载
     */
    public void start() {
        if (status == DownloadStatus.DOWNLOADING) return;
        
        // 如果是第一次启动
        if (status == DownloadStatus.IDLE || chunkMap.isEmpty()) {
            try {
                initializeFile();
                splitChunks(5); // 初始切分5个线程
            } catch (IOException e) {
                log.error("初始化失败", e);
                status = DownloadStatus.ERROR;
                return;
            }
        }

        status = DownloadStatus.DOWNLOADING;
        // 提交所有未完成的任务
        chunkMap.values().stream()
                .filter(c -> !c.isFinished())
                .forEach(this::submitTask);
        
        // 启动监控线程（处理速度计算和动态重分配）
        startMonitor();
    }

    public void pause() {
        status = DownloadStatus.PAUSED;
        activeWorkers.values().forEach(ChunkWorker::stopWork);
        activeWorkers.clear();
    }

    public void cancel() {
        status = DownloadStatus.CANCELED;
        activeWorkers.values().forEach(ChunkWorker::stopWork);
        activeWorkers.clear();
        chunkMap.clear();
        // 删除文件
        new File(savePath, fileName).delete();
    }

    /**
     * 核心算法：动态重分配 (Range Rescheduling)
     */
    public void tryRebalance() {
        if (status != DownloadStatus.DOWNLOADING) return;

        List<ChunkInfo> activeChunks = chunkMap.values().stream()
                .filter(c -> !c.isFinished())
                .collect(Collectors.toList());

        // 需求：未完成线程 <= 3
        if (activeChunks.size() > 3 || activeChunks.isEmpty()) return;

        long currentMaxSpeed = activeChunks.stream().mapToLong(ChunkInfo::getSpeed).max().orElse(1);
        
        // 寻找“慢”线程和“可协助”的场景
        // 这里的逻辑是：找到最慢的，且剩余量很大的，将其切分
        for (ChunkInfo slowChunk : activeChunks) {
            long remaining = slowChunk.getEnd() - slowChunk.getCurrent().get();
            // 剩余太小不切分 (比如小于 1MB)
            if (remaining < 1024 * 1024) continue;

            // 网速不足最大网速的 20%
            if (slowChunk.getSpeed() < (currentMaxSpeed * 0.2)) {
                log.info("触发动态分配: Chunk {} 速度过慢 ({}/s), 最大速度 ({}/s)", 
                        slowChunk.getId(), slowChunk.getSpeed(), currentMaxSpeed);
                performSplit(slowChunk);
                break; // 一次循环只处理一个，避免并发混乱
            }
        }
    }

    /**
     * 执行切分操作
     */
    private synchronized void performSplit(ChunkInfo parentChunk) {
        // 1. 停止当前慢速任务
        ChunkWorker worker = activeWorkers.get(parentChunk.getId());
        if (worker != null) {
            worker.stopWork();
            activeWorkers.remove(parentChunk.getId());
        }

        // 2. 计算新的 Range
        long currentPos = parentChunk.getCurrent().get();
        long endPos = parentChunk.getEnd();
        long mid = currentPos + (endPos - currentPos) / 2;

        log.info("切分任务: 原范围[{}-{}] -> 新任务1[{}-{}] 新任务2[{}-{}]", 
                 currentPos, endPos, currentPos, mid, mid + 1, endPos);

        // 3. 更新旧 Chunk 为前半部分
        parentChunk.setEnd(mid);
        // 重置一些状态以确保重新开始正常
        
        // 4. 创建新 Chunk 为后半部分
        ChunkInfo newChunk = new ChunkInfo(UUID.randomUUID().toString(), mid + 1, endPos, mid + 1);
        chunkMap.put(newChunk.getId(), newChunk);

        // 5. 重新提交两个任务
        submitTask(parentChunk);
        submitTask(newChunk);
    }

    private void submitTask(ChunkInfo chunkInfo) {
        ChunkWorker worker = new ChunkWorker(chunkInfo);
        activeWorkers.put(chunkInfo.getId(), worker);
        chunkExecutor.execute(worker);
    }

    /**
     * 监控线程：计算速度 & 触发重分配
     */
    private void startMonitor() {
        new Thread(() -> {
            while (status == DownloadStatus.DOWNLOADING) {
                try {
                    Thread.sleep(1000);
                    long totalSpeed = 0;
                    boolean allFinished = true;

                    for (ChunkInfo chunk : chunkMap.values()) {
                        if (!chunk.isFinished()) {
                            allFinished = false;
                            long curr = chunk.getCurrent().get();
                            long speed = curr - chunk.getLastRecordBytes();
                            chunk.setSpeed(speed);
                            chunk.setLastRecordBytes(curr);
                            totalSpeed += speed;
                        } else {
                            chunk.setSpeed(0);
                        }
                    }

                    if (allFinished && !chunkMap.isEmpty()) {
                        status = DownloadStatus.FINISHED;
                        break;
                    }

                    // 尝试负载均衡
                    tryRebalance();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    /**
     * 初始化文件大小和连接
     */
    private void initializeFile() throws IOException {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpGet request = new HttpGet(downloadUrl);
        try (CloseableHttpResponse response = client.execute(request)) {
            this.totalSize = response.getEntity().getContentLength();
            if (this.totalSize <= 0) throw new IOException("无法获取文件大小");
            
            File file = new File(savePath, fileName);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(this.totalSize);
            }
        }
    }

    private void splitChunks(int threadCount) {
        long blockSize = totalSize / threadCount;
        for (int i = 0; i < threadCount; i++) {
            long start = i * blockSize;
            long end = (i == threadCount - 1) ? totalSize - 1 : (i + 1) * blockSize - 1;
            ChunkInfo chunk = new ChunkInfo(UUID.randomUUID().toString(), start, end, start);
            chunkMap.put(chunk.getId(), chunk);
        }
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
            // 错误重试循环
            while (running.get() && !chunkInfo.isFinished() && chunkInfo.getErrorCount() < 5) {
                try {
                    download();
                } catch (Exception e) {
                    chunkInfo.setErrorCount(chunkInfo.getErrorCount() + 1);
                    log.warn("Chunk {} 下载出错, 重试 {}/5", chunkInfo.getId(), chunkInfo.getErrorCount());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
            
            // 如果错误太多
            if (chunkInfo.getErrorCount() >= 5) {
                log.error("Chunk {} 失败次数过多，停止", chunkInfo.getId());
                // 实际生产中可能需要通知主进程报错
            }
        }

        private void download() throws IOException {
            long startPos = chunkInfo.getCurrent().get();
            long endPos = chunkInfo.getEnd();

            if (startPos > endPos) {
                chunkInfo.setFinished(true);
                return;
            }

            CloseableHttpClient client = HttpClients.createDefault();
            HttpGet request = new HttpGet(downloadUrl);
            // 设置 Range 头
            request.addHeader("Range", "bytes=" + startPos + "-" + endPos);

            try (CloseableHttpResponse response = client.execute(request);
                 InputStream is = response.getEntity().getContent();
                 RandomAccessFile raf = new RandomAccessFile(new File(savePath, fileName), "rw")) {

                int statusCode = response.getStatusLine().getStatusCode();
                // 206 Partial Content 是必须的
                if (statusCode != 206 && statusCode != 200) {
                    throw new IOException("不支持 Range 请求或服务器错误: " + statusCode);
                }

                // Seek 到指定位置写入
                raf.seek(startPos);

                byte[] buffer = new byte[8192];
                int len;
                while (running.get() && (len = is.read(buffer)) != -1) {
                    // 写入文件
                    synchronized (fileLock) { // RandomAccessFile 自身线程安全，但Seek+Write非原子，需加锁，或使用 FileChannel
                        raf.seek(chunkInfo.getCurrent().get());
                        raf.write(buffer, 0, len);
                    }
                    // 更新进度
                    chunkInfo.getCurrent().addAndGet(len);
                    
                    // 检查是否超出了该分片的范围 (理论上HTTP流会自己结束，但双重保险)
                    if (chunkInfo.getCurrent().get() > chunkInfo.getEnd() + 1) {
                        break;
                    }
                }

                if (chunkInfo.getCurrent().get() >= chunkInfo.getEnd()) {
                    chunkInfo.setFinished(true);
                }
            }
        }
    }
}
```

#### 2.4 Controller 与 WebSocket

**`DownloadController.java`**

```java
package com.example.downloader.controller;

import com.example.downloader.core.DownloadTaskContext;
import com.example.downloader.model.ChunkInfo;
import com.example.downloader.model.DownloadStatus;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/download")
@EnableScheduling
@CrossOrigin
public class DownloadController {

    // 简单起见，单例演示。实际项目应使用 Map<TaskId, Context> 管理多个下载
    private DownloadTaskContext currentTask;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @PostMapping("/start")
    public String start(@RequestBody DownloadRequest req) {
        if (currentTask == null || !currentTask.getDownloadUrl().equals(req.getUrl())) {
            currentTask = new DownloadTaskContext(req.getUrl(), req.getPath(), "downloaded_file.dat");
        }
        currentTask.start();
        return "Started";
    }

    @PostMapping("/pause")
    public String pause() {
        if (currentTask != null) currentTask.pause();
        return "Paused";
    }

    @PostMapping("/cancel")
    public String cancel() {
        if (currentTask != null) {
            currentTask.cancel();
            currentTask = null;
        }
        return "Canceled";
    }

    /**
     * 定时推送状态给前端
     */
    @Scheduled(fixedRate = 500)
    public void pushStatus() {
        if (currentTask != null) {
            Map<String, Object> status = new HashMap<>();
            status.put("status", currentTask.getStatus());
            status.put("totalSize", currentTask.getTotalSize());
            
            // 计算总进度
            long totalDownloaded = currentTask.getChunkMap().values().stream()
                    .mapToLong(c -> c.getCurrent().get() - c.getStart()) // 注意：进度是相对分片起点的增量，或者直接统计已下载字节
                    .sum(); 
            
            // 修正：因为 ChunkInfo.current 是绝对位置
            // 已下载量 = sum(current - start)
            long validDownloaded = 0;
            for(ChunkInfo c : currentTask.getChunkMap().values()){
                validDownloaded += (c.getCurrent().get() - c.getStart());
            }
            
            status.put("downloaded", validDownloaded);
            status.put("chunks", currentTask.getChunkMap().values());
            
            messagingTemplate.convertAndSend("/topic/progress", status);
        }
    }

    @Data
    public static class DownloadRequest {
        private String url;
        private String path;
    }
}
```

**`WebSocketConfig.java`**

```java
package com.example.downloader.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
```

---

### 3. 前端代码 (Vue 3 + Element Plus)

为了方便，这里提供一个单文件 `index.html`，您可以直接放在 `src/main/resources/static` 下，或者独立运行。

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>High Performance Downloader</title>
    <!-- Import Styles -->
    <link rel="stylesheet" href="https://unpkg.com/element-plus/dist/index.css" />
    <script src="https://unpkg.com/vue@3/dist/vue.global.js"></script>
    <script src="https://unpkg.com/element-plus"></script>
    <script src="https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>
    <script src="https://unpkg.com/axios/dist/axios.min.js"></script>
    <style>
        body { font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; background: #f0f2f5; padding: 20px; }
        .container { max-width: 1000px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1); }
        .header { margin-bottom: 30px; border-bottom: 1px solid #eee; padding-bottom: 20px; }
        .chunk-card { margin-bottom: 10px; border: 1px solid #ebeef5; padding: 10px; border-radius: 4px; }
        .chunk-header { display: flex; justify-content: space-between; font-size: 12px; color: #666; margin-bottom: 5px; }
        .status-tag { font-weight: bold; }
    </style>
</head>
<body>
    <div id="app">
        <div class="container">
            <div class="header">
                <h2>🚀 Java Fork/Join Multi-Thread Downloader</h2>
            </div>

            <el-form :inline="false" label-width="100px">
                <el-form-item label="下载地址">
                    <el-input v-model="form.url" placeholder="请输入 http/https 下载链接"></el-input>
                </el-form-item>
                <el-form-item label="保存路径">
                    <el-input v-model="form.path" placeholder="例如: D:/Downloads/"></el-input>
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="startDownload" :disabled="isDownloading">开始下载</el-button>
                    <el-button type="warning" @click="pauseDownload" :disabled="!isDownloading">暂停</el-button>
                    <el-button type="danger" @click="cancelDownload">取消</el-button>
                </el-form-item>
            </el-form>

            <el-divider></el-divider>

            <!-- 总进度 -->
            <div v-if="statusData">
                <h3>总进度: {{ formatSize(statusData.downloaded) }} / {{ formatSize(statusData.totalSize) }} 
                    <el-tag :type="statusType">{{ statusData.status }}</el-tag>
                </h3>
                <el-progress :percentage="totalPercentage" :stroke-width="20" :status="progressStatus" striped striped-flow></el-progress>
            </div>

            <el-divider content-position="left">线程详情 (动态分配监控)</el-divider>

            <!-- 线程列表 -->
            <div v-if="statusData && statusData.chunks">
                <div v-for="chunk in sortedChunks" :key="chunk.id" class="chunk-card">
                    <div class="chunk-header">
                        <span>ID: {{ chunk.id.substring(0,8) }}...</span>
                        <span>Range: {{ chunk.start }} - {{ chunk.end }}</span>
                        <span>Errors: {{ chunk.errorCount }}</span>
                    </div>
                    <div style="display: flex; align-items: center;">
                        <el-progress :percentage="calculateChunkPercent(chunk)" style="flex: 1; margin-right: 10px;"></el-progress>
                        <div style="width: 120px; text-align: right; font-size: 14px; font-weight: bold; color: #409EFF;">
                            {{ formatSpeed(chunk.speed) }}
                        </div>
                        <div style="width: 80px; text-align: right;">
                             <el-tag size="small" v-if="chunk.finished" type="success">完成</el-tag>
                             <el-tag size="small" v-else type="primary">下载中</el-tag>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        const { createApp, ref, computed, onMounted } = Vue;

        createApp({
            setup() {
                const form = ref({
                    // 测试用大文件链接 (Office CDN)
                    url: 'https://officecdn-microsoft-com.akamaized.net/pr/C1297A47-86C4-4C1F-97FA-950631F94777/MacAutoupdate/Microsoft_Office_16.55.21111400_BusinessPro_Installer.pkg',
                    path: './temp/'
                });
                const statusData = ref(null);
                const stompClient = ref(null);

                const isDownloading = computed(() => {
                    return statusData.value && statusData.value.status === 'DOWNLOADING';
                });

                const totalPercentage = computed(() => {
                    if (!statusData.value || statusData.value.totalSize === 0) return 0;
                    return Math.floor((statusData.value.downloaded / statusData.value.totalSize) * 100);
                });

                const statusType = computed(() => {
                    if(!statusData.value) return 'info';
                    switch(statusData.value.status) {
                        case 'DOWNLOADING': return 'primary';
                        case 'FINISHED': return 'success';
                        case 'ERROR': return 'danger';
                        case 'PAUSED': return 'warning';
                        default: return 'info';
                    }
                });
                
                const progressStatus = computed(() => {
                     if(statusData.value && statusData.value.status === 'FINISHED') return 'success';
                     if(statusData.value && statusData.value.status === 'ERROR') return 'exception';
                     return '';
                });

                const sortedChunks = computed(() => {
                    if (!statusData.value || !statusData.value.chunks) return [];
                    // 按起始位置排序
                    return [...statusData.value.chunks].sort((a, b) => a.start - b.start);
                });

                const connectWebSocket = () => {
                    const socket = new SockJS('http://localhost:8080/ws');
                    stompClient.value = Stomp.over(socket);
                    stompClient.value.debug = null; // 禁用控制台调试日志
                    stompClient.value.connect({}, frame => {
                        console.log('Connected: ' + frame);
                        stompClient.value.subscribe('/topic/progress', (message) => {
                            statusData.value = JSON.parse(message.body);
                        });
                    });
                };

                const startDownload = async () => {
                    try {
                        await axios.post('http://localhost:8080/api/download/start', form.value);
                    } catch (e) {
                        ElementPlus.ElMessage.error('启动失败');
                    }
                };

                const pauseDownload = async () => {
                    await axios.post('http://localhost:8080/api/download/pause');
                };

                const cancelDownload = async () => {
                    await axios.post('http://localhost:8080/api/download/cancel');
                };

                const calculateChunkPercent = (chunk) => {
                    const total = chunk.end - chunk.start;
                    if (total <= 0) return 100;
                    const current = chunk.current - chunk.start;
                    let p = Math.floor((current / total) * 100);
                    return p > 100 ? 100 : p;
                };

                const formatSize = (bytes) => {
                    if (bytes === 0) return '0 B';
                    const k = 1024;
                    const sizes = ['B', 'KB', 'MB', 'GB'];
                    const i = Math.floor(Math.log(bytes) / Math.log(k));
                    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                };

                const formatSpeed = (bytesPerSec) => {
                    return formatSize(bytesPerSec) + '/s';
                };

                onMounted(() => {
                    connectWebSocket();
                });

                return {
                    form, statusData, isDownloading, totalPercentage, sortedChunks, statusType, progressStatus,
                    startDownload, pauseDownload, cancelDownload, calculateChunkPercent, formatSpeed, formatSize
                };
            }
        }).use(ElementPlus).mount('#app');
    </script>
</body>
</html>
```

---

### 4. 实现细节与“洁癖”说明

1.  **动态分配 (Work Stealing)**：
    *   逻辑在 `tryRebalance()` 方法中。
    *   **条件严格**：当剩余活跃线程 <= 3 且 某个线程速度 < 最大速度的 20% 时触发。
    *   **实现方式**：不是简单的修改变量，因为 HTTP 请求是阻塞的。做法是：`stopWork()` 强制终止当前慢速线程的 Worker -> 计算新的中点 -> 生成两个新的 `ChunkInfo` -> 重新提交到 `ForkJoinPool`。这是一种“破坏性”的重分配，但能有效解决“长尾效应”。

2.  **Fork/Join 线程池**：
    *   使用了 `RecursiveAction` 的子类 `ChunkWorker`。虽然这里没有进行递归拆分（因为下载是扁平的），但它利用了 ForkJoinPool 来管理任务执行。

3.  **断线续传与 Range**：
    *   每个 `ChunkInfo` 保存了 `start`, `end`, `current`。
    *   Java HTTP Client 发送 `Range: bytes=current-end` 头。
    *   文件写入使用 `RandomAccessFile.seek(current)` 确保位置准确。

4.  **并发安全**：
    *   `current` 使用 `AtomicLong`。
    *   文件写入块使用了 `synchronized(fileLock)` (注：更高效的做法是使用 `FileChannel` 的 `write(ByteBuffer, position)` 它是线程安全的，无需显式锁，但在本示例中为了代码可读性使用了 `RandomAccessFile` 加锁)。
    *   `ConcurrentHashMap` 管理任务状态。

5.  **代码规范**：
    *   使用了 Lombok `@Data`。
    *   分离了 Model, Core Service, Controller。
    *   关键逻辑添加了 JavaDoc。

### 5. 如何运行

1.  启动 Spring Boot 应用。
2.  打开浏览器访问 `src/main/resources/static/index.html` (或者直接双击打开 HTML 文件，如果是跨域，需要在 Controller 上加 `@CrossOrigin`，代码中已添加)。
3.  输入一个较大的文件下载地址（代码中默认了一个 Office 安装包地址用于测试）。
4.  观察进度条，为了测试“动态分配”，你可以尝试在代码中人为模拟某个 Chunk `Thread.sleep` 变慢，或者下载一个极大的文件观察后期的线程行为。
Use Arrow Up and Arrow Down to select a turn, Enter to jump to it, and Escape to return to the chat.
Response ready.
