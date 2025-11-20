package com.example.downloader.core;

import com.example.downloader.entity.DownloadRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * HTTP客户端工厂
 * <p>
 * 负责创建支持代理配置的HTTP客户端
 * </p>
 */
@Slf4j
public class HttpClientFactory {

    /**
     * 创建支持代理的 HttpClient
     */
    public CloseableHttpClient createHttpClient(DownloadRecord record) {
        HttpClientBuilder builder = HttpClients.custom();

        // 配置代理
        String proxyType = record.getProxyType();
        String proxyHost = record.getProxyHost();
        Integer proxyPort = record.getProxyPort();

        if (proxyType != null && proxyHost != null && proxyPort != null) {
            if ("HTTP".equalsIgnoreCase(proxyType)) {
                // HTTP 代理
                HttpHost proxy = new HttpHost(proxyHost, proxyPort);
                RequestConfig config = RequestConfig.custom()
                        .setProxy(proxy)
                        .setConnectTimeout(10000)
                        .setSocketTimeout(30000)
                        .setRedirectsEnabled(true)
                        .build();
                builder.setDefaultRequestConfig(config);
                log.info("使用 HTTP 代理: {}:{}", proxyHost, proxyPort);
            } else if ("SOCKS".equalsIgnoreCase(proxyType)) {
                // SOCKS 代理需要通过系统属性设置
                System.setProperty("socksProxyHost", proxyHost);
                System.setProperty("socksProxyPort", String.valueOf(proxyPort));
                log.info("使用 SOCKS 代理: {}:{}", proxyHost, proxyPort);

                RequestConfig config = RequestConfig.custom()
                        .setConnectTimeout(10000)
                        .setSocketTimeout(30000)
                        .setRedirectsEnabled(true)
                        .build();
                builder.setDefaultRequestConfig(config);
            }
        } else {
            // 无代理配置
            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(10000)
                    .setSocketTimeout(30000)
                    .setRedirectsEnabled(true)
                    .build();
            builder.setDefaultRequestConfig(config);
        }

        return builder.build();
    }
}