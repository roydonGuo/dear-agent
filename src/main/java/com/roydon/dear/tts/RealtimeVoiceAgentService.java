package com.roydon.dear.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 🌟 核心服务：实时语音 Agent
 *
 * 功能：
 * 1. 使用 OpenAI 兼容模式调用阿里云对话模型
 * 2. 使用阿里云 TTS API 进行流式语音合成
 * 3. 通过 SSE 同时推送文本和音频数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeVoiceAgentService {

    private final OpenAiChatModel chatModel;
    private final AlibabaTtsService ttsService;
    private final ObjectMapper objectMapper;

    /**
     * 流式对话 + 流式语音合成
     */
    public Flux<ServerSentEvent<String>> streamAgentWithVoice(String userMessage) {
        return streamAgentWithVoice(userMessage, null, "default");
    }

    /**
     * 流式对话 + 流式语音合成（支持自定义参数）
     *
     * @param userMessage 用户消息
     * @param voice 音色：Cherry(女声), Ethan(男声) 等
     * @param sessionId 会话ID
     */
    public Flux<ServerSentEvent<String>> streamAgentWithVoice(
            String userMessage,
            String voice,
            String sessionId) {

        log.info("🎯 开始处理 - 会话: {}, 消息: {}", sessionId, userMessage);

        AtomicReference<StringBuilder> fullTextAccumulator = new AtomicReference<>(new StringBuilder());

        // 使用 OpenAI 兼容模式调用阿里云对话模型
        return ChatClient.builder(chatModel)
            .build()
            .prompt()
            .user(userMessage)
            .system("""
                你是一个友好的AI助手，请用简洁、自然的语言回答用户问题。
                回答时请注意：
                1. 回答要简洁明了，适合语音播报
                2. 避免使用 Markdown 格式符号
                3. 使用自然的标点符号分割句子
                """)
            .stream()
            .content()
            // 按句子分割，优化 TTS 调用
            .windowUntil(this::isEndOfSentence)
            .timeout(Duration.ofSeconds(120))
            .flatMap(sentenceFlux -> processSentence(sentenceFlux, fullTextAccumulator, voice))
            .doOnNext(event -> log.debug("📤 发送事件: {}", event.event()))
            .doOnError(error -> log.error("❌ 流式处理错误: ", error))
            .doOnComplete(() -> log.info("✅ 流式处理完成 - 完整文本长度: {}",
                fullTextAccumulator.get().length()));
    }

    /**
     * 处理单个句子：生成文本事件和语音事件
     */
    private Flux<ServerSentEvent<String>> processSentence(
            Flux<String> sentenceFlux,
            AtomicReference<StringBuilder> accumulator,
            String voice) {

        return sentenceFlux
            .collectList()
            .flatMapMany(sentences -> {
                String sentence = String.join("", sentences);
                if (sentence == null || sentence.trim().isEmpty()) {
                    return Flux.empty();
                }

                String currentSentence = sentence.trim();
                accumulator.get().append(currentSentence);
                log.info("📝 处理句子 [{}]: {}", accumulator.get().length(),
                    currentSentence.length() > 50 ? currentSentence.substring(0, 50) + "..." : currentSentence);

                // 1. 立即发送文本事件
                ServerSentEvent<String> textEvent = ServerSentEvent.<String>builder()
                    .event("text")
                    .data(toJson(Map.of(
                        "text", currentSentence,
                        "timestamp", System.currentTimeMillis()
                    )))
                    .build();

                // 2. 异步生成语音流（不阻塞文本流）
                Flux<ServerSentEvent<String>> audioEventFlux = ttsService
                    .streamSynthesize(currentSentence, voice)
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(audioBytes -> ServerSentEvent.<String>builder()
                        .event("audio")
                        .data(toJson(Map.of(
                            "audio", Base64.getEncoder().encodeToString(audioBytes),
                            "format", "wav",
                            "timestamp", System.currentTimeMillis()
                        )))
                        .build())
                    .onErrorResume(error -> {
                        log.warn("⚠️ 语音合成失败，跳过: {}", error.getMessage());
                        return Flux.empty();
                    });

                // 合并：先发送文本，再发送音频
                return Flux.concat(
                    Flux.just(textEvent),
                    audioEventFlux
                );
            });
    }

    /**
     * 判断是否为句子结尾
     */
    private boolean isEndOfSentence(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        char lastChar = chunk.charAt(chunk.length() - 1);
        return lastChar == '。' || lastChar == '！' || lastChar == '？' ||
               lastChar == '.' || lastChar == '!' || lastChar == '?' ||
               lastChar == '；' || lastChar == ';' || lastChar == '…';
    }

    /**
     * 对象转 JSON
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败: ", e);
            return "{}";
        }
    }
}
