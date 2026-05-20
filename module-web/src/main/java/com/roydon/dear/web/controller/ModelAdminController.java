package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.model.provider.ModelProvider;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.entity.ModelConfig;
import com.roydon.dear.session.service.ModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelAdminController {

    private final ModelConfigService configService;
    private final ModelRegistry registry;
    private final List<ModelProvider> providers;

    @GetMapping("/config")
    @Operation(summary = "模型配置列表")
    public BaseResult<List<ModelConfig>> list(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) Boolean enabled) {
        List<ModelConfig> list = configService.listAllOrdered(category, enabled);
        return BaseResult.newSuccess(list);
    }

    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增模型配置")
    public BaseResult<ModelConfig> create(@RequestBody ModelConfig cfg) {
        cfg.setId(null);
        validateProvider(cfg);
        configService.save(cfg);
        configService.evictListCache();
        // todo 保存后要将模型刷新到缓存
        registry.refresh(cfg.getId());
        return BaseResult.newSuccess(cfg);
    }

    @GetMapping("/config/{id}")
    @Operation(summary = "获取模型配置")
    public BaseResult<ModelConfig> detail(@PathVariable Long id) {
        return BaseResult.newSuccess(configService.getById(id));
    }

    @PutMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑模型配置（热刷新）")
    public BaseResult<ModelConfig> update(@PathVariable Long id, @RequestBody ModelConfig cfg) {
        cfg.setId(id);
        validateProvider(cfg);
        configService.updateById(cfg);
        configService.evictListCache();
        registry.refresh(id);
        return BaseResult.newSuccess(cfg);
    }

    @DeleteMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除模型配置")
    public BaseResult<Void> delete(@PathVariable Long id) {
        ModelConfig cfg = configService.getById(id);
        if (cfg == null) {
            return BaseResult.newError("配置不存在");
        }
        registry.remove(cfg.getName());
        configService.removeById(id);
        configService.evictListCache();
        return BaseResult.newSuccess();
    }

    @PostMapping("/test")
    @Operation(summary = "测试模型连接")
    public BaseResult<String> testConnection(@RequestBody ModelConfig cfg) {
        try {
            ModelProvider provider = providers.stream()
                    .filter(p -> p.supports(cfg.getProvider()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("不支持的供应商: " + cfg.getProvider()));

            switch (cfg.getCategory()) {
                case "chat":
                    ChatModel chatModel = provider.createChatModel(cfg);
                    String resp = ChatClient.builder(chatModel).build()
                            .prompt()
                            .user("回复 ok 即可，不要多余内容")
                            .call()
                            .content();
                    return BaseResult.newSuccess("连接成功, 模型响应: " + resp);
                case "embedding":
                    EmbeddingModel embeddingModel = provider.createEmbeddingModel(cfg);
                    if (embeddingModel == null) {
                        return BaseResult.newError("供应商 " + cfg.getProvider() + " 不支持 Embedding 模型");
                    }
                    float[] helloWorlds = embeddingModel.embed("hello world");
                    log.info("hello world embedding: {}", helloWorlds);
                    return BaseResult.newSuccess("连接成功，模型维度：" + helloWorlds.length);
                case "multi":
                    ChatModel multimodalChatModel = provider.createChatModel(cfg);
                    var userMessage = UserMessage.builder()
                            .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                            .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new URI("http://43.240.221.8:9000/dear-agent/chat/a4c913a2d9174d308f4539b4a89ba32e.png"))))
                            .build();
                    var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
                    String multiResp = response.getResult().getOutput().getText();

                    if (multiResp == null || multiResp.trim().isEmpty()) {
                        return BaseResult.newSuccess("[无法识别图片内容]");
                    }
                    return BaseResult.newSuccess("连接成功, 模型响应: " + multiResp.trim());
                default:
                    throw new IllegalArgumentException("不支持的模型类别: " + cfg.getCategory());
            }
        } catch (Exception e) {
            log.error("模型连接测试失败", e);
            return BaseResult.newError("连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/providers")
    @Operation(summary = "获取支持的供应商列表")
    public BaseResult<List<ProviderVO>> listProviders() {
        return BaseResult.newSuccess(
                providers.stream()
                        .map(p -> new ProviderVO(p.getProviderName(), p.getProviderIcon(), p.getProviderOrder()))
                        .sorted(Comparator.comparingInt(ProviderVO::order))
                        .collect(Collectors.toList()));
    }

    public record ProviderVO(String name, String icon, Integer order) {
    }

    private void validateProvider(ModelConfig cfg) {
        boolean supported = providers.stream().anyMatch(p -> p.supports(cfg.getProvider()));
        if (!supported) {
            throw new IllegalArgumentException(
                    "不支持的供应商: " + cfg.getProvider() + "，可用: "
                            + providers.stream().map(ModelProvider::getProviderName).collect(Collectors.joining(", ")));
        }
    }
}
