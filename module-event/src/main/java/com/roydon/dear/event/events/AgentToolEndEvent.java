package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class AgentToolEndEvent extends AgentEvent {
    private final String agentId;
    private final String id;
    private final boolean success;
    private final String result;

    public AgentToolEndEvent(String agentId, String id, String result) {
        super("agent_tool_end", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.id = id;
        this.success = true;
        this.result = result;
    }

    public String getAgentId() { return agentId; }
    public String getId() { return id; }
    public boolean isSuccess() { return success; }
    public String getResult() { return result; }
}
