package com.roydon.dear.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class AgentEvent {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final String eventId;
    private final long timestamp;
    @JsonIgnore
    private final AgentPhase phase;
    private final String type;

    protected AgentEvent(String type, AgentPhase phase) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.phase = phase;
    }

    public String getEventId() { return eventId; }
    public long getTimestamp() { return timestamp; }
    public String getType() { return type; }

    @JsonIgnore
    public AgentPhase getPhase() { return phase; }

    public String getPhaseName() { return phase.name(); }

    public String toSseJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event: " + type, e);
        }
    }
}
