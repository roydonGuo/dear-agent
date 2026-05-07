package com.roydon.dear.enums;

import com.roydon.dear.util.ArrayValuable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * AI 模型类型的枚举
 */
@Getter
@RequiredArgsConstructor
public enum AiModelTypeEnum implements ArrayValuable<String> {

    CHAT("1", "对话"),

    EMBEDDING("2", "向量"),
    RERANK("3", "重排序"),

    VOICE("4", "语音"),

    IMAGE("5", "绘图"),
    VIDEO("6", "视频"),
    ;

    /**
     * 类型
     */
    private final String type;
    /**
     * 类型名
     */
    private final String name;

    public static final String[] ARRAYS = Arrays.stream(values()).map(AiModelTypeEnum::getType).toArray(String[]::new);

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
