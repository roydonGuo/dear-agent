package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.Map;

public class AgentToolStartEvent extends AgentEvent {
    private final String agentId;
    private final String id;
    private final String name;
    private final Map<String, Object> input;

    public AgentToolStartEvent(String agentId, String id, String name, Map<String, Object> input) {
        super("agent_tool_start", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.id = id;
        this.name = name;
        this.input = input;
    }

    public String getAgentId() { return agentId; }
    public String getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getInput() { return input; }
}
