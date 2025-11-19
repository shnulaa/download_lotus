package com.example.downloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类
 * <p>
 * 高性能多线程下载器应用入口
 * </p>
 */
@SpringBootApplication
public class DownloaderApplication {

    /**
     * 主方法入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DownloaderApplication.class, args);
    }
}
