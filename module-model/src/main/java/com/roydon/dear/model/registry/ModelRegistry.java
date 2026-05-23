package com.roydon.dear.model.registry;

import com.roydon.dear.session.entity.ModelConfig;
import com.roydon.dear.session.service.ModelConfigService;
import com.roydon.dear.model.provider.ModelProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRegistry {

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    /**
     * 各分类对应模型
     * eg：
     * chat: deepseek-v4-pro
     * embedding: text-embedding-v4
     */
    private final Map<String, String> defaultModelNames = new ConcurrentHashMap<>();

    private final List<ModelProvider> providers;
    private final ModelConfigService configService;

    @PostConstruct
    public void init() {
        List<ModelConfig> enabledConfigs = configService.listEnabled();
        for (ModelConfig cfg : enabledConfigs) {
            defaultModelNames.putIfAbsent(cfg.getCategory(), cfg.getName());
            log.debug("模型配置就绪: {} ({})", cfg.getName(), cfg.getCategory());
        }
        log.info("ModelRegistry 初始化完成, 共 {} 条模型配置", enabledConfigs.size());
    }

    public ChatModel getChatModel(String name) {
        return chatModelCache.computeIfAbsent(name, this::createChatModel);
    }

    public ChatModel getDefaultChatModel(String category) {
        String name = defaultModelNames.get(category);
        if (name == null) {
            throw new IllegalStateException("没有找到 " + category + " 类型的默认模型");
        }
        // 创建对应类型的模型

        return getChatModel(name);
    }

    public EmbeddingModel getEmbeddingModel(String name) {
        return embeddingModelCache.computeIfAbsent(name, this::createEmbeddingModel);
    }

    public EmbeddingModel getDefaultEmbeddingModel(String category) {
        String name = defaultModelNames.get(category);
        if (name == null) {
            throw new IllegalStateException("没有找到 " + category + " 类型的默认模型");
        }
        return getEmbeddingModel(name);
    }

    public synchronized void refresh(Long configId) {
        ModelConfig cfg = configService.getById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("模型配置不存在: " + configId);
        }
        ChatModel old = chatModelCache.remove(cfg.getName());
        if (old instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
        EmbeddingModel oldEmbedding = embeddingModelCache.remove(cfg.getName());
        if (oldEmbedding instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
        if (Boolean.TRUE.equals(cfg.getEnabled())) {
            defaultModelNames.put(cfg.getCategory(), cfg.getName());
        } else {
            defaultModelNames.remove(cfg.getCategory(), cfg.getName());
        }
        log.info("模型配置已刷新: {}", cfg.getName());
    }

    public void remove(String name) {
        ChatModel old = chatModelCache.remove(name);
        if (old instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
        EmbeddingModel oldEmbedding = embeddingModelCache.remove(name);
        if (oldEmbedding instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
        defaultModelNames.values().removeIf(name::equals);
    }

    @PreDestroy
    public void destroy() {
        chatModelCache.values().forEach(cm -> {
            if (cm instanceof DisposableBean bean) {
                try { bean.destroy(); } catch (Exception ignored) {}
            }
        });
        chatModelCache.clear();
        embeddingModelCache.values().forEach(em -> {
            if (em instanceof DisposableBean bean) {
                try { bean.destroy(); } catch (Exception ignored) {}
            }
        });
        embeddingModelCache.clear();
        defaultModelNames.clear();
    }

    private ChatModel createChatModel(String name) {
        ModelConfig cfg = configService.getByName(name);
        if (cfg == null) {
            throw new IllegalArgumentException("模型配置不存在: " + name);
        }
        ModelProvider provider = providers.stream()
                .filter(p -> p.supports(cfg.getProvider()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的供应商: " + cfg.getProvider()));

        log.info("创建 ChatModel 实例: {} (provider={}, model={})", name, cfg.getProvider(), cfg.getModel());
        return provider.createChatModel(cfg);
    }

    private EmbeddingModel createEmbeddingModel(String name) {
        ModelConfig cfg = configService.getByName(name);
        if (cfg == null) {
            throw new IllegalArgumentException("模型配置不存在: " + name);
        }
        ModelProvider provider = providers.stream()
                .filter(p -> p.supports(cfg.getProvider()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的供应商: " + cfg.getProvider()));

        log.info("创建 EmbeddingModel 实例: {} (provider={}, model={})", name, cfg.getProvider(), cfg.getModel());
        return provider.createEmbeddingModel(cfg);
    }
}
