package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
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
}
