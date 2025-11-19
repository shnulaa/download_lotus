package com.example.downloader.controller;

import com.example.downloader.core.DownloadTaskContext;
import com.example.downloader.entity.DownloadRecord;
import com.example.downloader.model.ChunkInfo;
import com.example.downloader.model.DownloadStatus;
import com.example.downloader.repo.DownloadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.alibaba.fastjson.JSON;

@RestController
@RequestMapping("/api/download")
@CrossOrigin
public class DownloadController {

    @Autowired
    private DownloadRepository repository;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 内存中活跃的任务 Context
    private final Map<String, DownloadTaskContext> activeContexts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 系统重启后，可以加载状态为 DOWNLOADING 的任务并恢复(此处简化为只加载状态，需手动点开始)
    }

    @PostMapping("/start")
    public String createTask(@RequestBody Map<String, Object> params) {
        String url = (String) params.get("url");
        String path = (String) params.get("path");
        int threads = (Integer) params.get("threads");

        DownloadRecord record = new DownloadRecord();
        record.setId(UUID.randomUUID().toString());
        record.setUrl(url);
        record.setSavePath(path);
        record.setCreatedTime(new Date());
        record.setStatus("IDLE");
        repository.save(record);

        DownloadTaskContext context = new DownloadTaskContext(record, repository, threads);
        activeContexts.put(record.getId(), context);
        context.start();

        return record.getId();
    }

    // 分页获取列表
    @GetMapping("/list")
    public Page<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        Page<DownloadRecord> pageData = repository.findAllByOrderByCreatedTimeDesc(PageRequest.of(page, size));
        return pageData.map(this::enrichRecordData);
    }

    // 下载到本地 (浏览器下载)
    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> downloadToLocal(@PathVariable String id) throws UnsupportedEncodingException {
        DownloadRecord record = repository.findById(id).orElse(null);
        if (record == null || !"FINISHED".equals(record.getStatus()))
            return ResponseEntity.notFound().build();

        File file = new File(record.getSavePath(), record.getFileName());
        if (!file.exists())
            return ResponseEntity.notFound().build();

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + URLEncoder.encode(file.getName(), "UTF-8") + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean deleteFile) {
        DownloadRecord record = repository.findById(id).orElse(null);
        if (record != null) {
            // 停止任务
            DownloadTaskContext ctx = activeContexts.get(id);
            if (ctx != null) {
                ctx.cancel();
                activeContexts.remove(id);
            }
            // 删除文件
            if (deleteFile && record.getFileName() != null) {
                new File(record.getSavePath(), record.getFileName()).delete();
            }
            // 删除记录
            repository.deleteById(id);
        }
    }

    @PostMapping("/{id}/{action}")
    public void control(@PathVariable String id, @PathVariable String action) {
        DownloadTaskContext ctx = activeContexts.get(id);
        // 如果内存没有（例如重启后），需重新构建Context (简化处理: 只有新建或一直运行的任务在内存)
        // 完整版需从DB重建Context
        if (ctx == null)
            return;

        if ("pause".equals(action))
            ctx.pause();
        // resume 逻辑需重新触发 start
    }

    // 定时推送数据
    @Scheduled(fixedRate = 800)
    public void pushProgress() {
        try {
            List<Map<String, Object>> updates = new ArrayList<>();
            for (DownloadTaskContext ctx : activeContexts.values()) {
                updates.add(enrichRecordData(ctx.getRecord()));
            }
            if (!updates.isEmpty()) {
                messagingTemplate.convertAndSend("/topic/progress", updates);
            }
        } catch (Exception e) {
            e.printStackTrace(); // 打印错误堆栈，防止定时任务终止
        }
    }

    private Map<String, Object> enrichRecordData(DownloadRecord r) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", r.getId());
        map.put("fileName", r.getFileName());
        map.put("url", r.getUrl());
        map.put("status", r.getStatus());
        map.put("totalSize", r.getTotalSize());
        map.put("createdTime", r.getCreatedTime());

        DownloadTaskContext ctx = activeContexts.get(r.getId());
        if (ctx != null) {
            map.put("status", ctx.getStatus()); // 实时状态
            map.put("speed", ctx.getGlobalSpeed()); // 实时速度
            map.put("supportRange", ctx.isSupportRange());

            long downloaded = 0;
            for (ChunkInfo c : ctx.getChunkMap().values()) {
                downloaded += (c.getCurrent().get() - c.getStart());
            }
            map.put("downloaded", downloaded);
            // 仅传输 ChunkInfo 必要的字段给前端绘图
            map.put("chunks", ctx.getChunkMap().values());
        } else {
            // 离线/历史任务
            map.put("speed", 0);
            map.put("downloaded", r.getTotalSize() == null ? 0 : r.getTotalSize());
            map.put("supportRange", r.getSupportRange() != null ? r.getSupportRange() : true);

            // 从数据库恢复chunks信息（用于显示线程颜色）
            if (r.getChunksJson() != null && !r.getChunksJson().isEmpty()) {
                try {
                    List<Map<String, Object>> chunkList = JSON.parseObject(r.getChunksJson(), List.class);
                    map.put("chunks", chunkList);
                } catch (Exception e) {
                    // 如果解析失败，不显示chunks
                }
            }
        }
        return map;
    }
}
