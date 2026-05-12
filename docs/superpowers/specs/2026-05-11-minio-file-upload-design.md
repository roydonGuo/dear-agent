# MinIO 文件上传模块设计

## 概述

新增 `module-core` 模块，集成 MinIO 对象存储，抽象 `FileStorage` 接口，实现通用文件上传功能。上传接口 API 统一放在 `module-web` 中。

## 现有资源

`application.yml` 已有 MinIO 配置：

```yaml
minio:
  url: http://127.0.0.1:9000/
  accessKey: roydon
  secretKey: roydon#Minio
  bucketName: dear-agent
  endpoint: http://127.0.0.1:9000/
```

Multipart 配置已就绪（最大 50MB）。

## 模块结构

### module-core（新建）

```
module-core/
├── pom.xml
└── src/main/java/com/roydon/dear/core/
    ├── config/
    │   └── MinioConfig.java
    └── service/
        ├── FileStorage.java
        └── impl/
            └── MinioFileStorage.java
```

#### `FileStorage` 接口

| 方法 | 说明 |
|------|------|
| `String upload(String bucket, String key, InputStream stream, long size, String contentType)` | 上传文件流，返回文件访问 URL |
| `void delete(String bucket, String key)` | 删除文件 |
| `String getFileUrl(String bucket, String key)` | 获取文件访问 URL |
| `String upload(MultipartFile file, String keyPrefix)` | 便捷上传：接收 Spring MultipartFile，自动生成 ObjectName，返回 URL |

**自动生成 ObjectName 规则**：`{keyPrefix}/{UUID}.{extension}`

#### `MinioConfig`

- `@ConfigurationProperties(prefix = "minio")` 绑定配置
- `@Bean` 创建 `MinioClient`
- `@Bean` 创建 `MinioFileStorage`

### module-web（改动）

新增 `FileUploadController.java`：

- `POST /api/file/upload`
- 参数：`file` (MultipartFile)，可选 `prefix`（目录前缀，默认 `uploads/`）
- 返回：`BaseResult<String>`（文件可访问 URL）
- 注入 `FileStorage` 接口使用

## POM 依赖

### 父 POM

```xml
<modules>
    <module>module-core</module>
</modules>
<dependencyManagement>
    <dependency>
        <groupId>com.roydon.dear</groupId>
        <artifactId>module-core</artifactId>
        <version>${project.version}</version>
    </dependency>
</dependencyManagement>
```

### module-core

```xml
<dependencies>
    <!-- MinIO SDK -->
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>8.5.17</version>
    </dependency>
    <!-- module-common（BaseResult 等） -->
    <dependency>
        <groupId>com.roydon.dear</groupId>
        <artifactId>module-common</artifactId>
    </dependency>
    <!-- Spring Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- 可选：配置处理器 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### module-web

在现有依赖中追加 `module-core`：

```xml
<dependency>
    <groupId>com.roydon.dear</groupId>
    <artifactId>module-core</artifactId>
</dependency>
```

## 错误处理

`MinioFileStorage` 中捕获 MinIO 异常，包装为 `RuntimeException`（或自定义业务异常），Controller 层统一由 Spring 异常处理机制捕获，返回 `BaseResult.newError("文件上传失败: xxx")`。
