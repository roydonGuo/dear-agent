package com.roydon.dear.event;

import reactor.core.publisher.Flux;

public class SseEventEmitter {

    private final AgentEventBus eventBus;

    public SseEventEmitter(AgentEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public Flux<String> toSseFlux() {
        return eventBus.asFlux()
                .map(AgentEvent::toSseJson)
                .doFinally(sig -> eventBus.complete());
    }
}
