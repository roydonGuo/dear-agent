package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.stereotype.Component;

@Component
public class ZhiPuAiModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "zhipuai";
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        ZhiPuAiApi zhiPuAiApi = ZhiPuAiApi.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .build();

        ZhiPuAiChatOptions.Builder optionsBuilder = ZhiPuAiChatOptions.builder()
                .model(config.getModel());

        if (config.getTemperature() != null) {
            optionsBuilder.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            optionsBuilder.maxTokens(config.getMaxTokens());
        }
        if (config.getTopP() != null) {
            optionsBuilder.topP(config.getTopP());
        }

        return new ZhiPuAiChatModel(zhiPuAiApi, optionsBuilder.build());
    }
}
