package com.example.downloader.util;

import java.io.File;
import java.net.URLDecoder;

/**
 * 文件工具类
 * <p>
 * 提供文件名处理和唯一性保证的相关功能
 * </p>
 */
public class FileUtils {

    /**
     * 从URL中提取文件名，如果不存在则生成默认名称
     *
     * @param originalUrl 原始下载URL
     * @return 提取的文件名
     */
    private static String extractFileNameFromUrl(String originalUrl) {
        try {
            // URL解码，处理中文等特殊字符
            String decodedUrl = URLDecoder.decode(originalUrl, "UTF-8");
            int lastSlashIndex = decodedUrl.lastIndexOf('/');

            if (lastSlashIndex >= 0 && lastSlashIndex < decodedUrl.length() - 1) {
                String fileName = decodedUrl.substring(lastSlashIndex + 1);
                // 去掉可能的URL参数
                int questionMarkIndex = fileName.indexOf('?');
                if (questionMarkIndex > 0) {
                    fileName = fileName.substring(0, questionMarkIndex);
                }
                // 去掉可能的锚点
                int hashIndex = fileName.indexOf('#');
                if (hashIndex > 0) {
                    fileName = fileName.substring(0, hashIndex);
                }

                // 如果文件名有效，返回
                if (fileName.length() > 0 && fileName.contains(".")) {
                    return fileName;
                }
            }
        } catch (Exception e) {
            // ignore decoding errors
        }

        return "downloaded_file";
    }

    /**
     * 获取不重复的文件名
     * <p>
     * 如果文件名已存在，则自动添加序号后缀
     * 例如: test.zip -> test(1).zip -> test(2).zip
     * </p>
     *
     * @param savePath 保存目录路径
     * @param originalUrl 原始下载URL
     * @return 唯一的文件名
     */
    public static String getUniqueFileName(String savePath, String originalUrl) {
        String fileName = extractFileNameFromUrl(originalUrl);
        File file = new File(savePath, fileName);

        // 如果文件不存在，直接返回
        if (!file.exists()) {
            return fileName;
        }

        // 分离文件名主体和后缀
        String nameBody;
        String extension;
        int dotIndex = fileName.lastIndexOf('.');

        if (dotIndex > 0) {
            nameBody = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        } else {
            nameBody = fileName;
            extension = "";
        }

        // 递增序号直到找到不存在的文件名
        int counter = 1;
        while (true) {
            String newName = nameBody + "(" + counter + ")" + extension;
            file = new File(savePath, newName);
            if (!file.exists()) {
                return newName;
            }
            counter++;
        }
    }
}
