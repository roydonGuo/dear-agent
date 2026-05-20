package com.roydon.dear.core.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface FileStorage {

    /**
     * 上传文件流
     *
     * @param bucket      存储桶
     * @param key         文件标识
     * @param stream      文件流
     * @param size        文件大小
     * @param contentType 文件类型
     * @return 文件访问 URL
     */
    String upload(String bucket, String key, InputStream stream, long size, String contentType, boolean publicUrl);

    /**
     * 上传 MultipartFile，自动生成 objectName
     *
     * @param file      MultipartFile
     * @param keyPrefix 文件路径前缀（如 "images/avatar"），自动拼接 UUID 和扩展名
     * @return 文件访问 URL
     */
    String upload(MultipartFile file, String keyPrefix, boolean publicUrl);

    /**
     * 上传 Base64 格式的头像/图片，自动解析并存储
     *
     * @param base64DataUrl 格式如 "data:image/png;base64,iVBOR..."
     * @param keyPrefix     文件路径前缀（如 "avatars"）
     * @return 文件访问 URL
     */
    String uploadBase64(String base64DataUrl, String keyPrefix);

    String uploadBase64(String base64DataUrl);

    /**
     * 上传 Base64 格式的图片并返回上传结果（URL + 存储路径）
     *
     * @param base64DataUrl 格式如 "data:image/png;base64,iVBOR..."
     * @param keyPrefix     文件路径前缀（如 "avatars"）
     * @return 上传结果（含文件访问 URL 和存储路径 key）
     */
    FileUploadResult uploadBase64WithResult(String base64DataUrl, String keyPrefix);

    FileUploadResult uploadBase64WithResult(String base64DataUrl);

    /**
     * 删除文件
     *
     * @param bucket 存储桶
     * @param key    文件标识
     */
    void delete(String bucket, String key);

    /**
     * 获取默认存储桶名称
     */
    String getDefaultBucket();

    /**
     * 获取文件访问 URL
     *
     * @param bucket 存储桶
     * @param key    文件标识
     * @return 文件访问 URL
     */
    String getFileUrl(String bucket, String key);

    String getPublicFileUrl(String bucket, String key);

    /**
     * 获取文件访问 URL（使用默认存储桶）
     *
     * @param key 文件标识
     * @return 文件访问 URL
     */
    String getFileUrl(String key);
}
