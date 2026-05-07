package com.roydon.dear.factory;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.func.Func0;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.roydon.dear.config.DearAiAutoConfiguration;
import com.roydon.dear.config.DearAiProperties;
import com.roydon.dear.enums.AiPlatformEnum;
import com.roydon.dear.model.baichuan.BaiChuanChatModel;
import com.roydon.dear.model.doubao.DouBaoChatModel;
import com.roydon.dear.model.hunyuan.HunYuanChatModel;
import com.roydon.dear.model.midjourney.api.MidjourneyApi;
import com.roydon.dear.model.siliconflow.SiliconFlowApiConstants;
import com.roydon.dear.model.siliconflow.SiliconFlowChatModel;
import com.roydon.dear.model.siliconflow.SiliconFlowImageApi;
import com.roydon.dear.model.siliconflow.SiliconFlowImageModel;
import com.roydon.dear.model.suno.api.SunoApi;
import com.roydon.dear.model.xinghuo.XingHuoChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.common.OpenAiApiConstants;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.springframework.ai.retry.RetryUtils.DEFAULT_RETRY_TEMPLATE;

/**
 * AI Model 模型工厂的实现类
 */
public class AiModelFactoryImpl implements AiModelFactory {

    @Override
    public ChatModel getOrCreateChatModel(AiPlatformEnum platform, String apiKey, String url) {
        String cacheKey = buildClientCacheKey(ChatModel.class, platform, apiKey, url);
        return Singleton.get(cacheKey, (Func0<ChatModel>) () -> {
            switch (platform) {
                case DEEP_SEEK:
                    return buildDeepSeekChatModel(apiKey);
                case DOU_BAO:
                    return buildDouBaoChatModel(apiKey);
                case HUN_YUAN:
                    return buildHunYuanChatModel(apiKey, url);
                case SILICON_FLOW:
                    return buildSiliconFlowChatModel(apiKey);
                case ZHI_PU:
                    return buildZhiPuChatModel(apiKey, url);
                case MINI_MAX:
                    return buildMiniMaxChatModel(apiKey, url);
                case XING_HUO:
                    return buildXingHuoChatModel(apiKey);
                case BAI_CHUAN:
                    return buildBaiChuanChatModel(apiKey);
                case OPENAI:
                    return buildOpenAiChatModel(apiKey, url);
                case OLLAMA:
                    return buildOllamaChatModel(url);
                default:
                    throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
            }
        });
    }

    @Override
    public ChatModel getDefaultChatModel(AiPlatformEnum platform) {
        switch (platform) {
            case DEEP_SEEK:
                return SpringUtil.getBean(DeepSeekChatModel.class);
            case DOU_BAO:
                return SpringUtil.getBean(DouBaoChatModel.class);
            case HUN_YUAN:
                return SpringUtil.getBean(HunYuanChatModel.class);
            case SILICON_FLOW:
                return SpringUtil.getBean(SiliconFlowChatModel.class);
            case ZHI_PU:
                return SpringUtil.getBean(ZhiPuAiChatModel.class);
            case MINI_MAX:
                return SpringUtil.getBean(MiniMaxChatModel.class);
            case XING_HUO:
                return SpringUtil.getBean(XingHuoChatModel.class);
            case BAI_CHUAN:
                return SpringUtil.getBean(BaiChuanChatModel.class);
            case OPENAI:
                return SpringUtil.getBean(OpenAiChatModel.class);
            case OLLAMA:
                return SpringUtil.getBean(OllamaChatModel.class);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public ImageModel getDefaultImageModel(AiPlatformEnum platform) {
        switch (platform) {
            case ZHI_PU:
                return SpringUtil.getBean(org.springframework.ai.zhipuai.ZhiPuAiImageModel.class);
            case SILICON_FLOW:
                return SpringUtil.getBean(SiliconFlowImageModel.class);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public ImageModel getOrCreateImageModel(AiPlatformEnum platform, String apiKey, String url) {
        switch (platform) {
            case ZHI_PU:
                return buildZhiPuAiImageModel(apiKey, url);
            case SILICON_FLOW:
                return buildSiliconFlowImageModel(apiKey, url);
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    @Override
    public MidjourneyApi getOrCreateMidjourneyApi(String apiKey, String url) {
        String cacheKey = buildClientCacheKey(MidjourneyApi.class, "midjourney", apiKey, url);
        return Singleton.get(cacheKey, (Func0<MidjourneyApi>) () -> {
            DearAiProperties.MidjourneyProperties properties = SpringUtil.getBean(DearAiProperties.class).getMidjourney();
            return new MidjourneyApi(url, apiKey, properties.getNotifyUrl());
        });
    }

    @Override
    public SunoApi getOrCreateSunoApi(String apiKey, String url) {
        String cacheKey = buildClientCacheKey(SunoApi.class, "suno", apiKey, url);
        return Singleton.get(cacheKey, (Func0<SunoApi>) () -> new SunoApi(url));
    }

    @Override
    public EmbeddingModel getOrCreateEmbeddingModel(AiPlatformEnum platform, String apiKey, String url, String model) {
        throw new UnsupportedOperationException("EmbeddingModel dynamic creation not yet implemented for platform: " + platform);
    }

    @Override
    public VectorStore getOrCreateVectorStore(Class<? extends VectorStore> type,
                                              EmbeddingModel embeddingModel,
                                              Map<String, Class<?>> metadataFields) {
        if (type == SimpleVectorStore.class) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }
        throw new IllegalArgumentException(StrUtil.format("未知类型({})", type));
    }

    // ========== 缓存 key ==========

    private static String buildClientCacheKey(Class<?> clazz, Object... params) {
        if (ArrayUtil.isEmpty(params)) {
            return clazz.getName();
        }
        return StrUtil.format("{}#{}", clazz.getName(), ArrayUtil.join(params, "_"));
    }

    // ========== ChatModel 创建方法 ==========

    private static DeepSeekChatModel buildDeepSeekChatModel(String apiKey) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder().apiKey(apiKey).build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(DeepSeekApi.DEFAULT_CHAT_MODEL)
                .temperature(0.7).build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    private static ChatModel buildDouBaoChatModel(String apiKey) {
        DearAiProperties.DouBaoProperties properties = new DearAiProperties.DouBaoProperties().setApiKey(apiKey);
        return new DearAiAutoConfiguration().buildDouBaoChatClient(properties);
    }

    private static ChatModel buildHunYuanChatModel(String apiKey, String url) {
        DearAiProperties.HunYuanProperties properties = new DearAiProperties.HunYuanProperties()
                .setBaseUrl(url).setApiKey(apiKey);
        return new DearAiAutoConfiguration().buildHunYuanChatClient(properties);
    }

    private static ChatModel buildSiliconFlowChatModel(String apiKey) {
        DearAiProperties.SiliconFlowProperties properties = new DearAiProperties.SiliconFlowProperties().setApiKey(apiKey);
        return new DearAiAutoConfiguration().buildSiliconFlowChatClient(properties);
    }

    private static ZhiPuAiChatModel buildZhiPuChatModel(String apiKey, String url) {
        ZhiPuAiApi.Builder zhiPuAiApiBuilder = ZhiPuAiApi.builder().apiKey(apiKey);
        if (StrUtil.isNotEmpty(url)) {
            zhiPuAiApiBuilder.baseUrl(url);
        }
        ZhiPuAiChatOptions options = ZhiPuAiChatOptions.builder().model(ZhiPuAiApi.DEFAULT_CHAT_MODEL).temperature(0.7).build();
        return new ZhiPuAiChatModel(zhiPuAiApiBuilder.build(), options, getToolCallingManager(), DEFAULT_RETRY_TEMPLATE,
                getObservationRegistry().getIfAvailable());
    }

    private static MiniMaxChatModel buildMiniMaxChatModel(String apiKey, String url) {
        MiniMaxApi miniMaxApi = StrUtil.isEmpty(url) ? new MiniMaxApi(apiKey)
                : new MiniMaxApi(url, apiKey);
        MiniMaxChatOptions options = MiniMaxChatOptions.builder()
                .model(MiniMaxApi.DEFAULT_CHAT_MODEL).temperature(0.7).build();
        return new MiniMaxChatModel(miniMaxApi, options);
    }

    private static XingHuoChatModel buildXingHuoChatModel(String key) {
        List<String> keys = StrUtil.split(key, '|');
        Assert.equals(keys.size(), 2, "XingHuoChatClient 的密钥需要 (appKey|secretKey) 格式");
        DearAiProperties.XingHuoProperties properties = new DearAiProperties.XingHuoProperties()
                .setAppKey(keys.get(0)).setSecretKey(keys.get(1));
        return new DearAiAutoConfiguration().buildXingHuoChatClient(properties);
    }

    private static BaiChuanChatModel buildBaiChuanChatModel(String apiKey) {
        DearAiProperties.BaiChuanProperties properties = new DearAiProperties.BaiChuanProperties().setApiKey(apiKey);
        return new DearAiAutoConfiguration().buildBaiChuanChatClient(properties);
    }

    private static OpenAiChatModel buildOpenAiChatModel(String openAiToken, String url) {
        url = StrUtil.blankToDefault(url, OpenAiApiConstants.DEFAULT_BASE_URL);
        OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(url).apiKey(openAiToken).build();
        return OpenAiChatModel.builder().openAiApi(openAiApi)
                .toolCallingManager(SpringUtil.getBean(ToolCallingManager.class)).build();
    }

    private static OllamaChatModel buildOllamaChatModel(String url) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(url).build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .toolCallingManager(getToolCallingManager())
                .build();
    }

    // ========== ImageModel 创建方法 ==========

    private static org.springframework.ai.zhipuai.ZhiPuAiImageModel buildZhiPuAiImageModel(String apiKey, String url) {
        org.springframework.ai.zhipuai.api.ZhiPuAiImageApi zhiPuAiApi = StrUtil.isEmpty(url)
                ? new org.springframework.ai.zhipuai.api.ZhiPuAiImageApi(apiKey)
                : new org.springframework.ai.zhipuai.api.ZhiPuAiImageApi(url, apiKey, org.springframework.web.client.RestClient.builder());
        return new org.springframework.ai.zhipuai.ZhiPuAiImageModel(zhiPuAiApi);
    }

    private static SiliconFlowImageModel buildSiliconFlowImageModel(String apiToken, String url) {
        url = StrUtil.blankToDefault(url, SiliconFlowApiConstants.DEFAULT_BASE_URL);
        SiliconFlowImageApi openAiApi = new SiliconFlowImageApi(url, apiToken);
        return new SiliconFlowImageModel(openAiApi);
    }

    private static ObjectProvider<ObservationRegistry> getObservationRegistry() {
        return new ObjectProvider<>() {

            @Override
            public ObservationRegistry getObject() throws BeansException {
                return SpringUtil.getBean(ObservationRegistry.class);
            }

        };
    }

    private static ObjectProvider<VectorStoreObservationConvention> getCustomObservationConvention() {
        return new ObjectProvider<>() {

            @Override
            public VectorStoreObservationConvention getObject() throws BeansException {
                return new DefaultVectorStoreObservationConvention();
            }

        };
    }

    private static BatchingStrategy getBatchingStrategy() {
        return SpringUtil.getBean(BatchingStrategy.class);
    }

    private static ToolCallingManager getToolCallingManager() {
        return SpringUtil.getBean(ToolCallingManager.class);
    }

    private static ObjectProvider<EmbeddingModelObservationConvention> getEmbeddingModelObservationConvention() {
        return new ObjectProvider<>() {

            @Override
            public EmbeddingModelObservationConvention getObject() throws BeansException {
                return SpringUtil.getBean(EmbeddingModelObservationConvention.class);
            }

        };
    }


}
