package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public String getProviderIcon() {
        return "icon-deepseek";
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .build();

        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
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

        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    public static void main(String[] args) {
        System.out.println("System.getProperty(\"user.dir\") = " + System.getProperty("user.dir") + "/.skills");
    }
}
