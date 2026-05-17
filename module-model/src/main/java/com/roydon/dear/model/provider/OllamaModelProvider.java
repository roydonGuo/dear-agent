package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Component;

@Component
public class OllamaModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public String getProviderIcon() {
        return "icon-ollama";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 7;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(config.getBaseUrl())
                .build();

        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                .model(config.getModel());

        if (config.getTemperature() != null) {
            optionsBuilder.temperature(config.getTemperature());
        }
        if (config.getTopP() != null) {
            optionsBuilder.topP(config.getTopP());
        }

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig config) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(config.getModel()).build())
                .build();
    }
}
