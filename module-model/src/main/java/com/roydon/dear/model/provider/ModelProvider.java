package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型供应商扩展点——新增供应商只需实现此接口并注册为 Spring Bean。
 */
public interface ModelProvider {

    String getProviderName();

    ChatModel createChatModel(ModelConfig config);

    default boolean supports(String provider) {
        return getProviderName().equals(provider);
    }
}
