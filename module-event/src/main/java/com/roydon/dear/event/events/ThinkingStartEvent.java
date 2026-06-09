package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ThinkingStartEvent extends AgentEvent {
    public ThinkingStartEvent() {
        super("thinking_start", AgentPhase.THINKING);
    }
}
