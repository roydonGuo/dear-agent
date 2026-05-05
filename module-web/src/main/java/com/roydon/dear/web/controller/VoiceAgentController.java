package com.roydon.dear.web.controller;

import com.roydon.dear.model.tts.RealtimeVoiceAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class VoiceAgentController {

    private final RealtimeVoiceAgentService agentService;

    @GetMapping(value = "/stream-with-voice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamWithVoice(
            @RequestParam String message,
            @RequestParam(required = false) String voice,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {
        log.info("收到请求 - 消息: {}, 音色: {}, 会话: {}", message, voice, sessionId);
        return agentService.streamAgentWithVoice(message, voice, sessionId);
    }

    @GetMapping(value = "/quick", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> quickChat(@RequestParam String message) {
        return agentService.streamAgentWithVoice(message);
    }

    @GetMapping("/health")
    public String health() { return "OK"; }
}
