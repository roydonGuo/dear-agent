package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class AgentTextEvent extends AgentEvent {
    private final String agentId;
    private final String text;

    public AgentTextEvent(String agentId, String text) {
        super("agent_text", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.text = text;
    }

    public String getAgentId() { return agentId; }
    public String getText() { return text; }
}
