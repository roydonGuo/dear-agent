package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ThinkingTextEvent extends AgentEvent {
    private final String text;

    public ThinkingTextEvent(String text) {
        super("thinking_text", AgentPhase.THINKING);
        this.text = text;
    }

    public String getText() { return text; }
}
