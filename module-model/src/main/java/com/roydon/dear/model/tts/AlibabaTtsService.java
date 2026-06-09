package com.roydon.dear.model.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlibabaTtsService {

    private final TtsConfig ttsConfig;
    private final ObjectMapper objectMapper;

    public Flux<byte[]> streamSynthesize(String text) {
        return streamSynthesize(text, ttsConfig.getVoice());
    }

    public Flux<byte[]> streamSynthesize(String text, String voice) {
        log.info("开始流式语音合成 - 模型: {}, 音色: {}", ttsConfig.getModel(), voice);

        WebClient webClient = WebClient.builder()
            .baseUrl(ttsConfig.getBaseUrl())
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsConfig.getModel());

        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice != null ? voice : ttsConfig.getVoice());
        input.put("language_type", ttsConfig.getLanguageType());
        input.put("speed", ttsConfig.getSpeed());
        input.put("pitch", ttsConfig.getPitch());
        input.put("emotion", ttsConfig.getEmotion());
        requestBody.put("input", input);

        return webClient.post()
            .header("Authorization", "Bearer " + ttsConfig.getApiKey())
            .header("X-DashScope-SSE", "enable")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnSubscribe(s -> log.debug("TTS 连接已建立"))
            .filter(line -> line != null && !line.isEmpty())
            .flatMap(this::parseSseResponse)
            .doOnComplete(() -> log.info("TTS 流式合成完成"))
            .doOnError(e -> log.error("TTS 流式合成失败: ", e))
            .onErrorResume(e -> {
                log.error("TTS 错误，返回空流: {}", e.getMessage());
                return Flux.empty();
            });
    }

    private Flux<byte[]> parseSseResponse(String line) {
        try {
            String jsonData = line;
            if (line.startsWith("data:")) {
                jsonData = line.substring(5).trim();
            }
            if (jsonData.isEmpty() || jsonData.equals("[DONE]")) {
                return Flux.empty();
            }
            JsonNode node = objectMapper.readTree(jsonData);
            int statusCode = node.has("status_code") ? node.get("status_code").asInt() : 200;
            if (statusCode != 200) {
                String message = node.has("message") ? node.get("message").asText() : "Unknown error";
                log.error("TTS API 错误: status_code={}, message={}", statusCode, message);
                return Flux.empty();
            }
            if (node.has("output") && node.get("output").has("audio")) {
                JsonNode audioNode = node.get("output").get("audio");
                if (audioNode.has("data") && !audioNode.get("data").asText().isEmpty()) {
                    String audioBase64 = audioNode.get("data").asText();
                    byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
                    return Flux.just(audioBytes);
                }
            }
            return Flux.empty();
        } catch (Exception e) {
            log.warn("解析 TTS 响应失败: {}", e.getMessage());
            return Flux.empty();
        }
    }

    public Mono<TtsResult> synthesize(String text) {
        return synthesize(text, ttsConfig.getVoice());
    }

    public Mono<TtsResult> synthesize(String text, String voice) {
        log.info("开始语音合成 - 模型: {}, 音色: {}", ttsConfig.getModel(), voice);

        WebClient webClient = WebClient.builder()
            .baseUrl(ttsConfig.getBaseUrl()).build();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsConfig.getModel());

        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice != null ? voice : ttsConfig.getVoice());
        input.put("language_type", ttsConfig.getLanguageType());
        input.put("speed", ttsConfig.getSpeed());
        input.put("pitch", ttsConfig.getPitch());
        input.put("emotion", ttsConfig.getEmotion());
        requestBody.put("input", input);

        return webClient.post()
            .header("Authorization", "Bearer " + ttsConfig.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(response -> {
                TtsResult result = new TtsResult();
                if (response.has("output") && response.get("output").has("audio")) {
                    JsonNode audioNode = response.get("output").get("audio");
                    if (audioNode.has("url")) result.setAudioUrl(audioNode.get("url").asText());
                    if (audioNode.has("data") && !audioNode.get("data").asText().isEmpty()) {
                        result.setAudioData(Base64.getDecoder().decode(audioNode.get("data").asText()));
                    }
                    if (audioNode.has("id")) result.setAudioId(audioNode.get("id").asText());
                }
                if (response.has("usage")) {
                    result.setCharacters(response.get("usage").get("characters").asInt());
                }
                return result;
            })
            .doOnSuccess(r -> log.info("语音合成完成"))
            .doOnError(e -> log.error("语音合成失败: ", e));
    }

    @lombok.Data
    public static class TtsResult {
        private String audioUrl;
        private byte[] audioData;
        private String audioId;
        private Integer characters;
    }
}
