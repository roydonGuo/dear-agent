package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class HunyuanModelProvider implements ModelProvider {

    public static final String BASE_URL = "https://api.hunyuan.cloud.tencent.com";

    public static final String MODEL_DEFAULT = "hunyuan-turbo";

    public static final String DEEP_SEEK_BASE_URL = "https://api.lkeap.cloud.tencent.com";

    public static final String DEEP_SEEK_MODEL_DEFAULT = "deepseek-v3";

    @Override
    public String getProviderName() {
        return "hunyuan";
    }

    @Override
    public String getProviderIcon() {
        return "icon-hunyuan";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 6;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        if (StringUtils.isBlank(config.getBaseUrl())) {
            config.setBaseUrl(StringUtils.startsWith(config.getModel(), "deepseek") ? DEEP_SEEK_BASE_URL : BASE_URL);
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(StringUtils.isBlank(config.getBaseUrl()) ? BASE_URL : config.getBaseUrl())
                .apiKey(config.getApiKey())
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
