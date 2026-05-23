package com.roydon.dear.knowledge.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件切分类型枚举
 */
@Getter
@AllArgsConstructor
public enum FileSplitType {

    /**
     * 按长度切分
     */
    LENGTH,

    /**
     * 按标题切分
     */
    TITLE,

    /**
     * 按正则切分
     */
    REGEX,

    /**
     * 智能切分
     */
    SMART,

    /**
     * 按分隔符切分
     */
    SEPARATOR,
    ;
}
