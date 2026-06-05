package com.roydon.dear.agent.registry;

import com.roydon.dear.agent.BaseAgent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRegistry {

    private final Map<String, BaseAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentMetadata> metadata = new ConcurrentHashMap<>();

    /**
     * 注册一个 Agent。
     * 通常在 Agent 的 @PostConstruct 或 Builder 中调用。
     */
    public void register(BaseAgent agent, AgentMetadata meta) {
        agents.put(meta.agentName(), agent);
        metadata.put(meta.agentName(), meta);
    }

    public BaseAgent getAgent(String name) {
        BaseAgent agent = agents.get(name);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + name);
        return agent;
    }

    public Set<String> getAgentNames() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    public AgentMetadata getMetadata(String name) {
        return metadata.get(name);
    }

    /**
     * 将所有已注册 Agent 导出为 ToolCallback 列表，
     * 可直接注入 ChatClient 的 toolCallbacks。
     */
    public List<ToolCallback> getAgentTools() {
        return agents.entrySet().stream()
                .map(e -> new AgentToolAdapter(e.getValue(), metadata.get(e.getKey())))
                .map(ToolCallback.class::cast)
                .toList();
    }

    /**
     * 按 role 过滤 Agent 工具
     */
    public List<ToolCallback> getAgentToolsByRole(String role) {
        return agents.entrySet().stream()
                .filter(e -> role.equals(metadata.get(e.getKey()).role()))
                .map(e -> new AgentToolAdapter(e.getValue(), metadata.get(e.getKey())))
                .map(ToolCallback.class::cast)
                .toList();
    }
}
