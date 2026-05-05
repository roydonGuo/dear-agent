package com.roydon.dear.tool.registry;

import com.roydon.dear.session.entity.McpServerConfig;
import com.roydon.dear.session.service.McpServerConfigService;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class McpRegistry implements InitializingBean, DisposableBean {

    private final Map<String, McpClientHolder> holders = new ConcurrentHashMap<>();

    @Autowired
    private McpServerConfigService configService;

    @Autowired
    private McpTransportFactory transportFactory;

    @Override
    public void afterPropertiesSet() {
        log.info("McpRegistry 初始化完成，已发现 {} 条 MCP 配置", configService.count());
    }

    public McpClientHolder getOrCreate(String name) {
        return holders.computeIfAbsent(name, this::initHolder);
    }

    public synchronized McpClientHolder refresh(Long configId) {
        McpServerConfig cfg = configService.getById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("配置不存在: " + configId);
        }
        close(cfg.getName());
        holders.remove(cfg.getName());
        if (Boolean.TRUE.equals(cfg.getEnabled())) {
            return getOrCreate(cfg.getName());
        }
        return null;
    }

    public synchronized void refreshAll() {
        holders.values().forEach(h -> close(h.getConfig().getName()));
        holders.clear();
    }

    public synchronized void remove(String name) {
        close(name);
        holders.remove(name);
    }

    public Map<String, McpClientHolder> getHolders() {
        configService.listEnabledOrdered().stream()
                .filter(McpServerConfig::getEnabled)
                .forEach(cfg -> {
                    try {
                        getOrCreate(cfg.getName());
                    } catch (Exception e) {
                        log.error("加载 MCP 工具失败: {}, 已跳过", cfg.getName(), e);
                    }
                });
        return new ConcurrentHashMap<>(holders);
    }

    public List<ToolCallback> getAllToolCallbacks() {
        return configService.listEnabledOrdered().stream()
                .filter(McpServerConfig::getEnabled)
                .flatMap(cfg -> {
                    try {
                        McpClientHolder holder = getOrCreate(cfg.getName());
                        return Arrays.stream(holder.getToolCallbacks());
                    } catch (Exception e) {
                        log.error("加载 MCP 工具失败: {}, 已跳过", cfg.getName(), e);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
    }

    private McpClientHolder initHolder(String name) {
        McpServerConfig cfg = configService.getByName(name);
        if (cfg == null) {
            throw new IllegalArgumentException("MCP 配置不存在: " + name);
        }
        log.info("初始化 MCP 客户端: {} ({})", name, cfg.getTransport());
        try {
            McpSyncClient client = transportFactory.create(cfg);
            client.initialize();
            log.info("MCP 客户端初始化成功: {}, 工具数: {}", name, client.listTools().tools().size());
            return new McpClientHolder(cfg, client);
        } catch (Exception e) {
            throw new RuntimeException("MCP 客户端初始化失败: " + name, e);
        }
    }

    private void close(String name) {
        McpClientHolder h = holders.get(name);
        if (h != null) {
            h.close();
        }
    }

    @Override
    public void destroy() {
        holders.values().forEach(McpClientHolder::close);
        holders.clear();
    }
}
