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
    CONVERTING("CONVERTING", "转换中"),
    CONVERTED("CONVERTED", "转换完成"),
    CHUNKED("CHUNKED", "分段完成"),
    VECTOR_STORED("VECTOR_STORED", "向量化完成");
    private final String code;
    private final String message;
}
