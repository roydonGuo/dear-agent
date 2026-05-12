package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class DashscopeModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "dashscope";
    }

    @Override
    public String getProviderIcon() {
        return "icon-dashscope";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 0;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
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

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }
}
