package com.roydon.dear.knowledge.process;

import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.enums.FileMineType;

import java.io.InputStream;

/**
 * 文件处理策略接口
 * 负责处理切分/向量化文件
 */
public interface FileProcessStrategy {

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileMineType mineType);

    /**
     * 处理文档转换 - Markdown 格式
     * 1. 从 MinIO 下载文件
     * 2. 调用文档解析接口获取md/zip
     * 3. 转换后的文档保存在minio上
     * 3. 更新文档状态和转换后的 URL
     */
    public void processFile(KnowledgeFileDO fileDO, InputStream inputStream);

}

