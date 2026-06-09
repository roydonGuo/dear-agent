package com.roydon.dear.event.events;

import com.roydon.dear.event.AgentEvent;
import com.roydon.dear.event.AgentPhase;

import java.util.List;

public class ReferenceEvent extends AgentEvent {
    private final List<SearchResultItem> items;

    public ReferenceEvent(List<SearchResultItem> items) {
        super("reference", AgentPhase.RESPONDING);
        this.items = items;
    }

    public List<SearchResultItem> getItems() { return items; }

    public record SearchResultItem(String url, String title, String content) {}
}
