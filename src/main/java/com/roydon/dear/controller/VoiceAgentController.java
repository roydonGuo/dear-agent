package com.roydon.dear.controller;

import com.roydon.dear.tts.RealtimeVoiceAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 实时语音 Agent 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class VoiceAgentController {

    private final RealtimeVoiceAgentService agentService;

    /**
     * 🌟 核心接口：流式对话 + 流式语音
     *
     * @param message   用户消息
     * @param voice     音色（可选）：Cherry(女声), Ethan(男声), etc.
     * @param sessionId 会话ID（可选）
     */
    @GetMapping(value = "/stream-with-voice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamWithVoice(
            @RequestParam String message,
            @RequestParam(required = false) String voice,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {

        log.info("📥 收到请求 - 消息: {}, 音色: {}, 会话: {}", message, voice, sessionId);

        return agentService.streamAgentWithVoice(message, voice, sessionId);
    }

    /**
     * 快速对话（使用默认参数）
     */
    @GetMapping(value = "/quick", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> quickChat(@RequestParam String message) {
        return agentService.streamAgentWithVoice(message);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
