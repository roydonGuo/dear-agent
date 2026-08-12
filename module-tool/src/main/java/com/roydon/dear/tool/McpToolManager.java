package com.roydon.dear.tool;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
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

    @Autowired
    private MathTools mathTools;

    private ToolCallback[] fileToolCallbacks;

    private ToolCallback[] mathToolCallbacks;

    @PostConstruct
    public void init() {
        MethodToolCallbackProvider fileProvider = MethodToolCallbackProvider.builder()
                .toolObjects(fileOperationTools).build();
        fileToolCallbacks = fileProvider.getToolCallbacks();
        MethodToolCallbackProvider mathProvider = MethodToolCallbackProvider.builder()
                .toolObjects(mathTools).build();
        mathToolCallbacks = mathProvider.getToolCallbacks();
        log.info("内置工具初始化完成，文件操作: {}, 数学计算: {}", fileToolCallbacks.length, mathToolCallbacks.length);
    }

    public ToolCallback[] getAllTools() {
        // 加载所有类型工具
        List<ToolCallback> all = new ArrayList<>();
        // 1、function call
        all.addAll(Arrays.asList(fileToolCallbacks));
        all.addAll(Arrays.asList(mathToolCallbacks));
        // 2、MCP 工具
        all.addAll(registry.getAllToolCallbacks());
        // 3、skill
//        if (skillsTool != null) {
//            all.addAll(Arrays.asList(skillsTool.getAllToolCallbacks()));
//        }
          /*
          1. 创建 ClasspathSkillRegistry，从 classpath:skills/ 下加载 Skill
         */
        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(System.getProperty("user.home") + "/.dear-agent/.skills")
                .build();

        /*
          2. 创建 read_skill 工具的 ToolCallback
             这是 LLM 在推理时主动调用的工具，用于按需读取某个 Skill 的完整 SKILL.md 内容
         */
        ToolCallback readSkillToolCallback = ReadSkillTool.createReadSkillToolCallback(skillRegistry, null);
        all.add(readSkillToolCallback);
        return all.toArray(ToolCallback[]::new);
    }

    public static void main(String[] args) {
        System.out.println("System.getProperty(\"user.home\") = " + System.getProperty("user.home"));
        // output: System.getProperty("user.home") = C:\Users\admin
    }

    public ToolCallback[] getFileTools() {
        return fileToolCallbacks;
    }

    public ToolCallback[] getSkillTools() {
        return skillsTool != null ? skillsTool.getAllToolCallbacks() : new ToolCallback[0];
    }
}
