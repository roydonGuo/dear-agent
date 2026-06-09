package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.List;
import java.util.Map;

public class KnowledgeEndEvent extends AgentEvent {
    private final List<KnowledgeItem> items;
    private final int count;

    public KnowledgeEndEvent(List<KnowledgeItem> items, int count) {
        super("knowledge_end", AgentPhase.KNOWLEDGE);
        this.items = items;
        this.count = count;
    }

    public List<KnowledgeItem> getItems() { return items; }
    public int getCount() { return count; }

    public record KnowledgeItem(double score, Map<String, Object> metadata) {}
}
