package com.roydon.dear.tool;

import com.roydon.dear.skill.tool.SkillsTool;
import com.roydon.dear.tool.registry.McpRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class McpToolManager {

    @Autowired(required = false)
    private SkillsTool skillsTool;

    @Autowired
    private McpRegistry registry;

    @Autowired
    private FileOperationTools fileOperationTools;

    private ToolCallback[] fileToolCallbacks;

    @PostConstruct
    public void init() {
        MethodToolCallbackProvider fileProvider = MethodToolCallbackProvider.builder()
                .toolObjects(fileOperationTools).build();
        fileToolCallbacks = fileProvider.getToolCallbacks();
        log.info("内置工具初始化完成，文件操作: {}", fileToolCallbacks.length);
    }

    public ToolCallback[] getAllTools() {
        // 加载所有类型工具
        List<ToolCallback> all = new ArrayList<>();
        // 1、function call
        all.addAll(Arrays.asList(fileToolCallbacks));
        // 2、MCP 工具
        all.addAll(registry.getAllToolCallbacks());
        // 3、skill
        if (skillsTool != null) {
            all.addAll(Arrays.asList(skillsTool.getAllToolCallbacks()));
        }
        return all.toArray(ToolCallback[]::new);
    }

    public ToolCallback[] getFileTools() {
        return fileToolCallbacks;
    }

    public ToolCallback[] getSkillTools() {
        return skillsTool != null ? skillsTool.getAllToolCallbacks() : new ToolCallback[0];
    }
}
