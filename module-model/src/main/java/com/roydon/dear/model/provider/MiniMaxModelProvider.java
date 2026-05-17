package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

@Component
public class MiniMaxModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "minimax";
    }

    @Override
    public String getProviderIcon() {
        return "icon-minimax";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 8;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        MiniMaxApi miniMaxApi;
        if (config.getBaseUrl() == null) {
            miniMaxApi = new MiniMaxApi(config.getApiKey());
        } else {
            miniMaxApi = new MiniMaxApi(config.getBaseUrl(), config.getApiKey());
        }

        MiniMaxChatOptions.Builder optionsBuilder = MiniMaxChatOptions.builder()
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

        return new MiniMaxChatModel(miniMaxApi, optionsBuilder.build());
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig config) {
        return null;
    }
}
