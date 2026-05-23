package com.roydon.dear.knowledge.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * FileSegmentStatus
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Getter
@AllArgsConstructor
public enum FileSegmentStatus {
    CHUNKED("CHUNKED", "分段完成"),
    VECTOR_STORED("VECTOR_STORED", "向量化完成");
    private final String code;
    private final String message;
}
