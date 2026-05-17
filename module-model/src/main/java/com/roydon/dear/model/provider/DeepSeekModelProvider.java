package com.roydon.dear.model.provider;

import com.roydon.dear.model.provider.reasoning.DeepSeekReasoningExchangeFilter;
import com.roydon.dear.model.provider.reasoning.ReasoningChatModelWrapper;
import com.roydon.dear.session.entity.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * DeepSeek 模型提供商。
 *
 * <p>针对 deepseek-reasoner (R1) 模型的多轮工具调用场景，
 * 通过 {@link ReasoningChatModelWrapper} + {@link DeepSeekReasoningExchangeFilter}
 * 修复 Spring AI 1.1.0 丢失 reasoning_content 导致 400 错误的问题。</p>
 */
@Component
public class DeepSeekModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelProvider.class);


    public static final String BASE_URL = "https://api.deepseek.com";

    public static final String MODEL_DEFAULT = "deepseek-chat";

    private final DeepSeekReasoningExchangeFilter reasoningFilter = new DeepSeekReasoningExchangeFilter();

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public String getProviderIcon() {
        return "icon-deepseek";
    }

    /**
     * 模型供应商顺序，用于前端排序。
     */
    @Override
    public Integer getProviderOrder() {
        return 4;
    }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        // 1. 构建带 reasoning_content 过滤器的 WebClient.Builder
        WebClient.Builder webClientBuilder = WebClient.builder()
                .filter(reasoningFilter);

        // 2. 构建 DeepSeekApi
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .webClientBuilder(webClientBuilder)
                .build();

        // 3. 构建 DeepSeekChatOptions
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

        // 4. 构建 DeepSeekChatModel 并用 ReasoningChatModelWrapper 包装
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(optionsBuilder.build())
                .build();

        log.info("DeepSeek 模型已初始化: model={}, reasoning_content 修复已启用", config.getModel());
        return new ReasoningChatModelWrapper(chatModel);
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig config) {
        return null;
    }

    // ===== 工具方法 =====

    public static WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .headers(headers -> headers
                        .remove("Transfer-Encoding")
                        .set("Connection", "close")
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public static void main(String[] args) {
        System.out.println("System.getProperty(\"user.dir\") = " + System.getProperty("user.dir") + "/.skills");
    }
}
