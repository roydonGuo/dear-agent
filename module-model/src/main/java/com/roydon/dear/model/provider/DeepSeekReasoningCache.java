package com.roydon.dear.model.provider;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 缓存 DeepSeek R1 响应的 reasoning_content，供下一轮工具调用请求时注入。
 * 使用 FIFO 队列匹配"请求-响应-请求-..."的串行时序。
 */
public class DeepSeekReasoningCache {

    /**
     * FIFO 队列：响应捕获时 push，请求注入时 pop。
     * 串行的多轮工具调用保证了 FIFO 的正确性。
     */
    private static final ConcurrentLinkedDeque<String> cache = new ConcurrentLinkedDeque<>();

    private DeepSeekReasoningCache() {
    }

    public static void push(String reasoningContent) {
        if (reasoningContent != null && !reasoningContent.isBlank()) {
            cache.push(reasoningContent);
        }
    }

    public static String poll() {
        return cache.poll();
    }

    public static void clear() {
        cache.clear();
    }
}
