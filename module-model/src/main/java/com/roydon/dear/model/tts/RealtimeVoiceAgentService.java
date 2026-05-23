package com.roydon.dear.model.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeVoiceAgentService {

    private final ModelRegistry modelRegistry;
    private final AlibabaTtsService ttsService;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<String>> streamAgentWithVoice(String userMessage) {
        return streamAgentWithVoice(userMessage, null, "default");
    }

    public Flux<ServerSentEvent<String>> streamAgentWithVoice(String userMessage, String voice, String sessionId) {
        log.info("开始处理 - 会话: {}, 消息: {}", sessionId, userMessage);

        ChatModel chatModel;
        try {
            chatModel = modelRegistry.getDefaultChatModel(ModelCategoryEnum.CHAT.getCode());
        } catch (IllegalStateException e) {
            log.warn("模型配置异常: {}", e.getMessage());
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(toJson(Map.of("error", "模型未配置：" + e.getMessage())))
                    .build());
        }

        AtomicReference<StringBuilder> fullTextAccumulator = new AtomicReference<>(new StringBuilder());

        return ChatClient.builder(chatModel).build()
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
            .windowUntil(this::isEndOfSentence)
            .timeout(Duration.ofSeconds(120))
            .flatMap(sentenceFlux -> processSentence(sentenceFlux, fullTextAccumulator, voice))
            .doOnNext(event -> log.debug("发送事件: {}", event.event()))
            .doOnError(error -> log.error("流式处理错误: ", error))
            .doOnComplete(() -> log.info("流式处理完成 - 完整文本长度: {}", fullTextAccumulator.get().length()));
    }

    private Flux<ServerSentEvent<String>> processSentence(
            Flux<String> sentenceFlux, AtomicReference<StringBuilder> accumulator, String voice) {
        return sentenceFlux.collectList().flatMapMany(sentences -> {
            String sentence = String.join("", sentences);
            if (sentence == null || sentence.trim().isEmpty()) return Flux.empty();
            String currentSentence = sentence.trim();
            accumulator.get().append(currentSentence);

            ServerSentEvent<String> textEvent = ServerSentEvent.<String>builder()
                .event("text")
                .data(toJson(Map.of("text", currentSentence, "timestamp", System.currentTimeMillis())))
                .build();

            Flux<ServerSentEvent<String>> audioEventFlux = ttsService
                .streamSynthesize(currentSentence, voice)
                .subscribeOn(Schedulers.boundedElastic())
                .map(audioBytes -> ServerSentEvent.<String>builder()
                    .event("audio")
                    .data(toJson(Map.of("audio", Base64.getEncoder().encodeToString(audioBytes),
                        "format", "wav", "timestamp", System.currentTimeMillis())))
                    .build())
                .onErrorResume(error -> { log.warn("语音合成失败，跳过: {}", error.getMessage()); return Flux.empty(); });

            return Flux.concat(Flux.just(textEvent), audioEventFlux);
        });
    }

    private boolean isEndOfSentence(String chunk) {
        if (chunk == null || chunk.isEmpty()) return false;
        char lastChar = chunk.charAt(chunk.length() - 1);
        return lastChar == '。' || lastChar == '！' || lastChar == '？'
            || lastChar == '.' || lastChar == '!' || lastChar == '?'
            || lastChar == '；' || lastChar == ';' || lastChar == '…';
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.error("JSON 序列化失败: ", e); return "{}"; }
    }
}
