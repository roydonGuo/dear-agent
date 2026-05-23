package com.roydon.dear.web.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class BusinessMetrics {

    private final Counter chatRequestCounter;
    private final Counter chatErrorCounter;
    private final Counter stopRequestCounter;
    private final Timer chatRequestTimer;

    public BusinessMetrics(MeterRegistry registry) {
        this.chatRequestCounter = Counter.builder("agent.chat.requests")
                .description("Agent chat request count")
                .register(registry);
        this.chatErrorCounter = Counter.builder("agent.chat.errors")
                .description("Agent chat error count")
                .register(registry);
        this.stopRequestCounter = Counter.builder("agent.stop.requests")
                .description("Agent stop request count")
                .register(registry);
        this.chatRequestTimer = Timer.builder("agent.chat.duration")
                .description("Agent chat request duration")
                .register(registry);
    }

    public void recordChatRequest() {
        chatRequestCounter.increment();
    }

    public void recordChatError() {
        chatErrorCounter.increment();
    }

    public void recordStopRequest() {
        stopRequestCounter.increment();
    }

    public void recordChatDuration(long durationMs) {
        chatRequestTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }
}
