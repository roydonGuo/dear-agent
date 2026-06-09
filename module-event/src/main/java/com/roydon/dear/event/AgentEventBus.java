package com.roydon.dear.event;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

public interface AgentEventBus {

    void publish(AgentEvent event);

    Flux<AgentEvent> asFlux();

    <T extends AgentEvent> Disposable on(Class<T> eventType, Consumer<T> listener);

    Disposable on(AgentPhase phase, Consumer<AgentEvent> listener);

    Disposable onAll(Consumer<AgentEvent> listener);

    AgentPhase currentPhase();

    void complete();
}
