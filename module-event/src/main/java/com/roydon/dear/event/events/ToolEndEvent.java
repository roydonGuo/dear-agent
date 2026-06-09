package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class ToolEndEvent extends AgentEvent {
    private final String id;
    private final String name;
    private final boolean success;
    private final String result;

    public ToolEndEvent(String id, String name, String result) {
        super("tool_end", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.success = true;
        this.result = result;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isSuccess() { return success; }
    public String getResult() { return result; }
}
