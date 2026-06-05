package com.roydon.dear.agent.registry;

import com.roydon.dear.agent.BaseAgent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class AgentToolAdapter implements ToolCallback {

    private final BaseAgent agent;
    private final AgentMetadata metadata;

    public AgentToolAdapter(BaseAgent agent, AgentMetadata metadata) {
        this.agent = agent;
        this.metadata = metadata;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(metadata.agentName())
                .description(metadata.description() + "\n输入格式：直接传入你需要该 Agent 完成的任务描述文本。")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "task": {
                              "type": "string",
                              "description": "需要该 Agent 完成的任务描述"
                            }
                          },
                          "required": ["task"]
                        }
                        """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            return metadata.callSync(toolInput);
        } catch (Exception e) {
            return "Agent [" + metadata.agentName() + "] 执行失败: " + e.getMessage();
        }
    }
}
