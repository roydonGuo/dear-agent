package com.roydon.dear.domain.agent;

/**
 * 搜索结果记录
 */
public record SearchResult(
        String url,
        String title,
        String content
) {
}
