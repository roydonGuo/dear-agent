package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class AnthropicModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public String getProviderIcon() {
        return "icon-anthropic";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 2;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        AnthropicApi anthropicApi = AnthropicApi.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .build();

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
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

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig config) {
        return null;
    }
}
