package com.roydon.dear.tts;

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

/**
 * 阿里云 TTS 服务
 * 使用 qwen3-tts-flash 模型进行流式语音合成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlibabaTtsService {

    private final TtsConfig ttsConfig;
    private final ObjectMapper objectMapper;

    /**
     * 流式语音合成
     *
     * @param text 待合成文本
     * @return 音频字节流（Base64 解码后的原始音频数据）
     */
    public Flux<byte[]> streamSynthesize(String text) {
        return streamSynthesize(text, ttsConfig.getVoice());
    }

    /**
     * 流式语音合成（支持自定义音色）
     *
     * @param text 待合成文本
     * @param voice 音色：Cherry, Ethan 等
     * @return 音频字节流
     */
    public Flux<byte[]> streamSynthesize(String text, String voice) {
        log.info("🔊 开始流式语音合成 - 模型: {}, 音色: {}", ttsConfig.getModel(), voice);
        log.info("📝 文本内容: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);

        WebClient webClient = WebClient.builder()
            .baseUrl(ttsConfig.getBaseUrl())
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsConfig.getModel());

        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice != null ? voice : ttsConfig.getVoice());
        input.put("language_type", ttsConfig.getLanguageType());
        requestBody.put("input", input);

        log.debug("📤 TTS 请求体: {}", requestBody);

        return webClient.post()
            .header("Authorization", "Bearer " + ttsConfig.getApiKey())
            .header("X-DashScope-SSE", "enable")  // 启用 SSE 流式输出
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnSubscribe(s -> log.debug("🔗 TTS 连接已建立"))
            .filter(line -> line != null && !line.isEmpty())
            .doOnNext(line -> log.trace("📥 TTS 原始响应: {}", line))
            .flatMap(this::parseSseResponse)
            .doOnComplete(() -> log.info("✅ TTS 流式合成完成"))
            .doOnError(e -> log.error("❌ TTS 流式合成失败: ", e))
            .onErrorResume(e -> {
                log.error("TTS 错误，返回空流: {}", e.getMessage());
                return Flux.empty();
            });
    }

    /**
     * 解析 SSE 响应
     */
    private Flux<byte[]> parseSseResponse(String line) {
        try {
            // 处理 SSE 格式：data: {...}
            String jsonData = line;
            if (line.startsWith("data:")) {
                jsonData = line.substring(5).trim();
            }

            if (jsonData.isEmpty() || jsonData.equals("[DONE]")) {
                return Flux.empty();
            }

            JsonNode node = objectMapper.readTree(jsonData);

            // 检查状态码
            int statusCode = node.has("status_code") ? node.get("status_code").asInt() : 200;
            if (statusCode != 200) {
                String message = node.has("message") ? node.get("message").asText() : "Unknown error";
                log.error("TTS API 错误: status_code={}, message={}", statusCode, message);
                return Flux.empty();
            }

            // 提取音频数据
            if (node.has("output") && node.get("output").has("audio")) {
                JsonNode audioNode = node.get("output").get("audio");

                // 流式输出时，音频数据在 data 字段（Base64 编码）
                if (audioNode.has("data") && !audioNode.get("data").asText().isEmpty()) {
                    String audioBase64 = audioNode.get("data").asText();
                    byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
                    log.debug("🎵 收到音频数据: {} bytes", audioBytes.length);
                    return Flux.just(audioBytes);
                }
            }

            return Flux.empty();

        } catch (Exception e) {
            log.warn("解析 TTS 响应失败: {}, 原始数据: {}", e.getMessage(), line);
            return Flux.empty();
        }
    }

    /**
     * 非流式语音合成（一次性返回完整音频 URL）
     */
    public Mono<TtsResult> synthesize(String text) {
        return synthesize(text, ttsConfig.getVoice());
    }

    /**
     * 非流式语音合成（支持自定义音色）
     */
    public Mono<TtsResult> synthesize(String text, String voice) {
        log.info("🔊 开始语音合成 - 模型: {}, 音色: {}", ttsConfig.getModel(), voice);

        WebClient webClient = WebClient.builder()
            .baseUrl(ttsConfig.getBaseUrl())
            .build();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ttsConfig.getModel());

        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice != null ? voice : ttsConfig.getVoice());
        input.put("language_type", ttsConfig.getLanguageType());
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

                    if (audioNode.has("url")) {
                        result.setAudioUrl(audioNode.get("url").asText());
                    }
                    if (audioNode.has("data") && !audioNode.get("data").asText().isEmpty()) {
                        String audioBase64 = audioNode.get("data").asText();
                        result.setAudioData(Base64.getDecoder().decode(audioBase64));
                    }
                    if (audioNode.has("id")) {
                        result.setAudioId(audioNode.get("id").asText());
                    }
                }

                if (response.has("usage")) {
                    result.setCharacters(response.get("usage").get("characters").asInt());
                }

                return result;
            })
            .doOnSuccess(result -> log.info("✅ 语音合成完成"))
            .doOnError(e -> log.error("❌ 语音合成失败: ", e));
    }

    /**
     * TTS 结果
     */
    @lombok.Data
    public static class TtsResult {
        private String audioUrl;
        private byte[] audioData;
        private String audioId;
        private Integer characters;
    }
}
