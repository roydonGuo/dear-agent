package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class TextDeltaEvent extends AgentEvent {
    private final String text;

    public TextDeltaEvent(String text) {
        super("text_delta", AgentPhase.RESPONDING);
        this.text = text;
    }

    public String getText() { return text; }
}
