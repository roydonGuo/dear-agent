package com.roydon.dear.knowledge.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库文件状态枚举
 * ：INIT, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/20
 **/
@Getter
@AllArgsConstructor
public enum KnowledgeFileStatus {
    INIT("INIT", "初始化"),
    CONVERTING("CONVERTING", "转换中"), // 指的是原始文档在用户点击索引后的下一个状态
    CONVERTED("CONVERTED", "转换完成"), // 指的是识别原始文档，转换为可索引的文本，例如markdown里的图片进行ocr识别，表格数据提取等等，会将一个转换过的文档保存到minio中，后续embedding model进行embed and store
    CHUNKED("CHUNKED", "分段完成"),
    VECTOR_STORED("VECTOR_STORED", "向量化完成");
    private final String code;
    private final String message;
}
