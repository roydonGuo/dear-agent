package com.roydon.dear.common.domain.agent;

import lombok.Getter;

@Getter
public enum RoundMode {
    UNKNOWN("未知"),
    FINAL_ANSWER("最终答案"),
    TOOL_CALL("工具调用");

    private final String desc;

    RoundMode(String desc) {
        this.desc = desc;
    }
}
