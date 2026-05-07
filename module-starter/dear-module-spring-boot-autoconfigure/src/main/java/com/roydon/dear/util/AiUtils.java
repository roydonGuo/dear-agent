package com.roydon.dear.util;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.roydon.dear.enums.AiPlatformEnum;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Spring AI 工具类
 */
public class AiUtils {

    public static final String TOOL_CONTEXT_LOGIN_USER = "LOGIN_USER";

    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens) {
        return buildChatOptions(platform, model, temperature, maxTokens, null);
    }

    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens, Set<String> toolNames) {
        toolNames = ObjUtil.defaultIfNull(toolNames, Collections.emptySet());
        switch (platform) {
            case ZHI_PU:
                return ZhiPuAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).build();
            case MINI_MAX:
                return MiniMaxChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).build();
            case OPENAI:
            case DEEP_SEEK:
            case DOU_BAO:
            case HUN_YUAN:
            case XING_HUO:
            case SILICON_FLOW:
            case BAI_CHUAN:
                return OpenAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).build();
            case AZURE_OPENAI:
                return AzureOpenAiChatOptions.builder().deploymentName(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).build();
            case OLLAMA:
                return OllamaChatOptions.builder().model(model).temperature(temperature).numPredict(maxTokens)
                        .toolNames(toolNames).build();
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens, Set<String> toolNames, Map<String, Object> toolContext) {
        toolNames = ObjUtil.defaultIfNull(toolNames, Collections.emptySet());
        switch (platform) {
            case ZHI_PU:
                return ZhiPuAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).toolContext(toolContext).build();
            case MINI_MAX:
                return MiniMaxChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).toolContext(toolContext).build();
            case OPENAI:
            case DEEP_SEEK:
            case DOU_BAO:
            case HUN_YUAN:
            case XING_HUO:
            case SILICON_FLOW:
            case BAI_CHUAN:
                return OpenAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).toolContext(toolContext).build();
            case AZURE_OPENAI:
                return AzureOpenAiChatOptions.builder().deploymentName(model).temperature(temperature).maxTokens(maxTokens)
                        .toolNames(toolNames).toolContext(toolContext).build();
            case OLLAMA:
                return OllamaChatOptions.builder().model(model).temperature(temperature).numPredict(maxTokens)
                        .toolNames(toolNames).toolContext(toolContext).build();
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    public static Message buildMessage(String type, String content) {
        if (MessageType.USER.getValue().equals(type)) {
            return new UserMessage(content);
        }
        if (MessageType.ASSISTANT.getValue().equals(type)) {
            return new AssistantMessage(content);
        }
        if (MessageType.SYSTEM.getValue().equals(type)) {
            return new SystemMessage(content);
        }
        if (MessageType.TOOL.getValue().equals(type)) {
            throw new UnsupportedOperationException("暂不支持 tool 消息：" + content);
        }
        throw new IllegalArgumentException(StrUtil.format("未知消息类型({})", type));
    }

    public static Map<String, Object> buildCommonToolContext(Long userId) {
        Map<String, Object> context = new HashMap<>();
        context.put(TOOL_CONTEXT_LOGIN_USER, userId);
        return context;
    }
}
