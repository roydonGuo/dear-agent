package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class AgentErrorEvent extends AgentEvent {
    private final String agentId;
    private final String error;

    public AgentErrorEvent(String agentId, String error) {
        super("agent_error", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.error = error;
    }

    public String getAgentId() { return agentId; }
    public String getError() { return error; }
}
