package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import com.roydon.dear.common.domain.mcp.McpServerVO;
import io.micrometer.core.annotation.Timed;
import com.roydon.dear.session.entity.McpServerConfig;
import com.roydon.dear.session.service.McpServerConfigService;
import com.roydon.dear.tool.McpToolManager;
import com.roydon.dear.tool.registry.McpClientHolder;
import com.roydon.dear.tool.registry.McpRegistry;
import com.roydon.dear.tool.registry.McpTransportFactory;
import io.modelcontextprotocol.client.McpSyncClient;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpAdminController {

    private final McpServerConfigService configService;
    private final McpRegistry registry;
    private final McpTransportFactory transportFactory;
    private final McpToolManager toolManager;

    @Timed(value = "mcp.config.list", description = "List MCP configs")
    @GetMapping("/config")
    @Operation(summary = "MCP 配置列表")
    public BaseResult<List<McpServerConfig>> list() {
        return BaseResult.newSuccess(configService.listAllOrdered());
    }

    @Timed(value = "mcp.config.create", description = "Create MCP config")
    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "新增 MCP 配置")
    public BaseResult<McpServerConfig> create(@RequestBody McpServerConfig cfg) {
        cfg.setId(null);
        configService.save(cfg);
        try {
            validateConnection(cfg);
            log.info("新增 MCP 配置并验证通过: {}", cfg.getName());
        } catch (Exception e) {
            cfg.setEnabled(false);
            configService.updateById(cfg);
            return BaseResult.newSuccess(cfg, "配置已保存，但连接测试失败: " + e.getMessage());
        }
        return BaseResult.newSuccess(cfg);
    }

    @Timed(value = "mcp.config.detail", description = "Get MCP config detail")
    @GetMapping("/config/{id}")
    @Operation(summary = "查询 MCP 配置")
    public BaseResult<McpServerConfig> detail(@PathVariable Long id) {
        return BaseResult.newSuccess(configService.getById(id));
    }

    @Timed(value = "mcp.config.update", description = "Update MCP config")
    @PutMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "编辑 MCP 配置")
    public BaseResult<McpServerConfig> update(@PathVariable Long id, @RequestBody McpServerConfig cfg) {
        cfg.setId(id);
        configService.updateById(cfg);
        registry.refresh(id);
        return BaseResult.newSuccess(cfg);
    }

    @Timed(value = "mcp.config.delete", description = "Delete MCP config")
    @DeleteMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除 MCP 配置")
    public BaseResult<Void> delete(@PathVariable Long id) {
        McpServerConfig cfg = configService.getById(id);
        if (cfg == null) {
            return BaseResult.newError("配置不存在");
        }
        registry.remove(cfg.getName());
        configService.removeById(id);
        return BaseResult.newSuccess();
    }

    @Timed(value = "mcp.test", description = "Test MCP connection")
    @PostMapping("/test")
    @Operation(summary = "测试 MCP 连接")
    public BaseResult<String> testConnection(@RequestBody McpServerConfig cfg) {
        try {
            validateConnection(cfg);
            return BaseResult.newSuccess("连接成功");
        } catch (Exception e) {
            return BaseResult.newError("连接失败: " + e.getMessage());
        }
    }

    @Timed(value = "mcp.refresh", description = "Refresh all MCP connections")
    @PostMapping("/refresh")
    @Operation(summary = "刷新全部 MCP 连接")
    public BaseResult<Void> refreshAll() {
        registry.refreshAll();
        return BaseResult.newSuccess();
    }

    @Timed(value = "mcp.tools", description = "List MCP tools")
    @GetMapping("/tools")
    @Operation(summary = "获取当前 MCP 工具列表（按 MCP 服务端分组）")
    public BaseResult<List<McpServerVO>> listTools() {
        List<McpServerVO> result = new ArrayList<>();

        // 1. MCP 远程工具（按 server 分组）
        Set<String> mcpToolNames = new HashSet<>();
        registry.getHolders().forEach((name, holder) -> {
            List<McpServerVO.McpToolVO> tools = Arrays.stream(holder.getToolCallbacks())
                    .map(tc -> {
                        ToolDefinition def = tc.getToolDefinition();
                        mcpToolNames.add(def.name());
                        return McpServerVO.McpToolVO.builder()
                                .name(def.name())
                                .description(def.description())
                                .build();
                    })
                    .toList();

            McpServerConfig cfg = holder.getConfig();
            result.add(McpServerVO.builder()
                    .name(cfg.getName())
                    .label(cfg.getLabel())
                    .description(cfg.getDescription())
                    .transport(cfg.getTransport())
                    .enabled(cfg.getEnabled())
                    .tools(tools)
                    .build());
        });

        // 2. 内置工具（排除已在 MCP 中出现的）
        List<McpServerVO.McpToolVO> builtinTools = Arrays.stream(toolManager.getAllTools())
                .filter(tc -> !mcpToolNames.contains(tc.getToolDefinition().name()))
                .map(tc -> {
                    ToolDefinition def = tc.getToolDefinition();
                    return McpServerVO.McpToolVO.builder()
                            .name(def.name())
                            .description(def.description())
                            .build();
                })
                .collect(Collectors.toList());
        if (!builtinTools.isEmpty()) {
            result.add(McpServerVO.builder()
                    .name("built-in")
                    .label("内置工具")
                    .description("系统内置的本地工具（文件操作、天气查询等）")
                    .transport("local")
                    .enabled(true)
                    .tools(builtinTools)
                    .build());
        }

        return BaseResult.newSuccess(result);
    }

    private void validateConnection(McpServerConfig cfg) {
        McpSyncClient client = transportFactory.create(cfg);
        try {
            client.initialize();
            int toolCount = client.listTools().tools().size();
            log.info("MCP 连接测试成功: {}, 工具数: {}", cfg.getName(), toolCount);
        } finally {
            client.close();
        }
    }
}
