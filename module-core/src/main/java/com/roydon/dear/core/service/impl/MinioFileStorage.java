package com.roydon.dear.core.service.impl;

import com.roydon.dear.core.service.FileStorage;
import com.roydon.dear.core.service.FileUploadResult;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.UUID;

public class MinioFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorage.class);

    private static final String DEFAULT_PREFIX = "uploads";

    private final MinioClient minioClient;
    private final String defaultBucket;

    public MinioFileStorage(MinioClient minioClient, String defaultBucket) {
        this.minioClient = minioClient;
        this.defaultBucket = defaultBucket;
    }

    @Override
    public String upload(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            ensureBucketExists(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
            return getFileUrl(bucket, key);
        } catch (Exception e) {
            log.error("MinIO upload failed, bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String upload(MultipartFile file, String keyPrefix) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String key = keyPrefix + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
        try (InputStream inputStream = file.getInputStream()) {
            return upload(defaultBucket, key, inputStream, file.getSize(), file.getContentType());
        } catch (Exception e) {
            log.error("MinIO multipart upload failed, filename={}", originalFilename, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadBase64(String base64DataUrl, String keyPrefix) {
        return doUploadBase64(base64DataUrl, keyPrefix).getUrl();
    }

    @Override
    public FileUploadResult uploadBase64WithResult(String base64DataUrl, String keyPrefix) {
        return doUploadBase64(base64DataUrl, keyPrefix);
    }

    @Override
    public FileUploadResult uploadBase64WithResult(String base64DataUrl) {
        return uploadBase64WithResult(base64DataUrl, DEFAULT_PREFIX);
    }

    private FileUploadResult doUploadBase64(String base64DataUrl, String keyPrefix) {
        if (base64DataUrl == null || !base64DataUrl.startsWith("data:")) {
            return new FileUploadResult(base64DataUrl, null);
        }
        // format: "data:image/jpeg;base64,/9j/4AAQ..."
        String[] parts = base64DataUrl.split(";base64,", 2);
        if (parts.length < 2) {
            throw new RuntimeException("无效的 Base64 图片格式");
        }
        String mimeType = parts[0].replace("data:", "");
        String extension = switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".jpg";
        };
        byte[] data = Base64.getDecoder().decode(parts[1]);
        String key = keyPrefix + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
        try (InputStream stream = new ByteArrayInputStream(data)) {
            String url = upload(defaultBucket, key, stream, data.length, mimeType);
            return new FileUploadResult(url, key);
        } catch (Exception e) {
            log.error("MinIO base64 upload failed", e);
            throw new RuntimeException("图片上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadBase64(String base64DataUrl) {
        return this.uploadBase64(base64DataUrl, DEFAULT_PREFIX);
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.error("MinIO delete failed, bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }

    @Override
    public String getFileUrl(String bucket, String key) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .method(Method.GET)
                    .build());
        } catch (Exception e) {
            log.error("MinIO getUrl failed, bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("文件地址获取失败: " + e.getMessage());
        }
    }

    @Override
    public String getFileUrl(String key) {
        return getFileUrl(defaultBucket, key);
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("存储桶检查/创建失败: " + e.getMessage());
        }
    }
}
