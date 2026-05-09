package com.roydon.dear.model.provider;

import com.roydon.dear.session.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型供应商扩展点——新增供应商只需实现此接口并注册为 Spring Bean。
 */
public interface ModelProvider {

    /**
     * 模型供应商名称，用于前端显示。
     */
    String getProviderName();

    /**
     * 模型供应商图标，用于前端显示。
     */
    String getProviderIcon();

    /**
     * 模型供应商顺序，用于前端排序。
     */
    Integer getProviderOrder();

//    String getProviderBaseUrl();

    ChatModel createChatModel(ModelConfig config);

    default boolean supports(String provider) {
        return getProviderName().equals(provider);
    }
}
