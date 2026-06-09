package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ThinkingEndEvent extends AgentEvent {
    private final long durationMs;

    public ThinkingEndEvent(long durationMs) {
        super("thinking_end", AgentPhase.THINKING);
        this.durationMs = durationMs;
    }

    public long getDurationMs() { return durationMs; }
}
