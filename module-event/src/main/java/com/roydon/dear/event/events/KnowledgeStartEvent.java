package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

public class KnowledgeStartEvent extends AgentEvent {
    public KnowledgeStartEvent() {
        super("knowledge_start", AgentPhase.KNOWLEDGE);
    }
}
