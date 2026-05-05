package com.roydon.dear.tool.registry;

import com.roydon.dear.session.entity.McpServerConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

@Slf4j
@Getter
public class McpClientHolder implements AutoCloseable {

    private final McpServerConfig config;
    private final McpSyncClient client;
    private final ToolCallback[] toolCallbacks;
    private final long createdAt;

    public McpClientHolder(McpServerConfig config, McpSyncClient client) {
        this.config = config;
        this.client = client;
        this.toolCallbacks = new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
        this.createdAt = System.currentTimeMillis();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 MCP 客户端失败: {}", config.getName(), e);
        }
    }
}
