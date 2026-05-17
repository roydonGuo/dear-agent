package com.roydon.dear.session.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型分类枚举
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/17
 **/
@Getter
@AllArgsConstructor
public enum ModelCategoryEnum {
    CHAT("chat", "对话"),
    EMBEDDING("embedding", "嵌入"),
    RERANK("rerank", "重排序"),
    TTS("tts", "语音合成"),
    STT("stt", "语音识别"),
    IMAGE("image", "图像"),
    VIDEO("video", "视频"),
    OTHER("other", "其他"),
    ;

    private final String code;
    private final String desc;

}
