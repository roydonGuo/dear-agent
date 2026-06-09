package com.roydon.dear.event;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DefaultAgentEventBus implements AgentEventBus {

    private final Sinks.Many<AgentEvent> sink = Sinks.many().replay().all();
    private final Flux<AgentEvent> flux = sink.asFlux();
    private final AtomicReference<AgentPhase> phaseRef = new AtomicReference<>();

    @Override
    public void publish(AgentEvent event) {
        phaseRef.set(event.getPhase());
        sink.tryEmitNext(event);
    }

    @Override
    public Flux<AgentEvent> asFlux() {
        return flux;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends AgentEvent> Disposable on(Class<T> eventType, Consumer<T> listener) {
        return flux.ofType(eventType).subscribe(listener::accept);
    }

    @Override
    public Disposable on(AgentPhase phase, Consumer<AgentEvent> listener) {
        return flux.filter(e -> e.getPhase() == phase).subscribe(listener::accept);
    }

    @Override
    public Disposable onAll(Consumer<AgentEvent> listener) {
        return flux.subscribe(listener::accept);
    }

    @Override
    public AgentPhase currentPhase() {
        return phaseRef.get();
    }

    @Override
    public void complete() {
        sink.tryEmitComplete();
    }
}
