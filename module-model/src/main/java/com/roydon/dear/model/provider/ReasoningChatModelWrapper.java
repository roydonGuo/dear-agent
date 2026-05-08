package com.roydon.dear.model.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * ChatModel 包装器：拦截 DeepSeek R1 的流式/非流式响应，
 * 从中提取 reasoning_content 并推入 DeepSeekReasoningCache，
 * 供下一轮工具调用请求时由 DeepSeekReasoningExchangeFilter 注入。
 */
public class ReasoningChatModelWrapper implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ReasoningChatModelWrapper.class);

    private final ChatModel delegate;

    public ReasoningChatModelWrapper(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        cacheReasoningIfNeeded(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        StringBuilder reasoningBuf = new StringBuilder();
        boolean[] hasToolCalls = {false};

        return delegate.stream(prompt)
                .doOnNext(chunk -> accumulateReasoning(chunk, reasoningBuf, hasToolCalls))
                .doOnComplete(() -> pushReasoning(reasoningBuf, hasToolCalls));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    // ===== 内部方法 =====

    private void accumulateReasoning(ChatResponse chunk, StringBuilder buf, boolean[] hasToolCalls) {
        if (chunk == null || chunk.getResult() == null) return;
        AssistantMessage output = chunk.getResult().getOutput();
        if (output == null) return;

        if (output instanceof DeepSeekAssistantMessage deepMsg) {
            String reasoning = deepMsg.getReasoningContent();
            if (reasoning != null && !reasoning.isBlank()) {
                buf.append(reasoning);
            }
        } else {
            Map<String, Object> metadata = output.getMetadata();
            if (metadata != null) {
                String reasoning = extractReasoning(metadata);
                if (reasoning != null) buf.append(reasoning);
            }
        }
        if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
            hasToolCalls[0] = true;
        }
    }

    private void pushReasoning(StringBuilder buf, boolean[] hasToolCalls) {
        String reasoning = buf.toString();
        if (!reasoning.isBlank() && hasToolCalls[0]) {
            DeepSeekReasoningCache.push(reasoning);
            log.debug("已缓存 reasoning_content({} chars)，准备下一轮工具调用", reasoning.length());
        }
    }

    private void cacheReasoningIfNeeded(ChatResponse response) {
        if (response == null) return;
        for (Generation gen : response.getResults()) {
            AssistantMessage output = gen.getOutput();
            if (output == null) continue;
            boolean hasToolCalls = output.getToolCalls() != null && !output.getToolCalls().isEmpty();
            if (!hasToolCalls) continue;

            String reasoning = null;
            if (output instanceof DeepSeekAssistantMessage deepMsg) {
                reasoning = deepMsg.getReasoningContent();
            }
            if (reasoning == null || reasoning.isBlank()) {
                reasoning = extractReasoning(output.getMetadata());
            }
            if (reasoning != null && !reasoning.isBlank()) {
                DeepSeekReasoningCache.push(reasoning);
                log.debug("已缓存 reasoning_content({} chars)，准备下一轮工具调用", reasoning.length());
                return;
            }
        }
    }

    private String extractReasoning(Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String key : List.of("reasoningContent", "reasoning", "reasoning_content", "thinking", "thought")) {
            Object value = metadata.get(key);
            if (value instanceof String str && !str.isBlank()) return str;
        }
        return null;
    }
}
