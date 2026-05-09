package com.roydon.dear.skill.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.skill.model.Skill;
import com.roydon.dear.skill.model.SkillParameter;
import com.roydon.dear.skill.repository.SkillRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SkillsTool — 遵循 spring-ai-agent-utils SkillsTool 范式。
 *
 * <p>核心机制：
 * <ol>
 *   <li>启动时扫描 ~/.dear-agent/.skills/ 下所有已启用的 SKILL.md</li>
 *   <li>注册一个名为 "skill" 的 ToolCallback，其 description 列出所有可用技能（供 AI 语义匹配）</li>
 *   <li>AI 调用 skill({@code {"command":"skill-name"}}) 时，加载完整 SKILL.md 内容返回</li>
 *   <li>可执行技能（FUNCTION 类型）额外生成独立 ToolCallback，通过 {@link #getSkillToolCallbacks()} 暴露</li>
 * </ol>
 */
@Slf4j
@Component
public class SkillsTool {

    /** Skill 工具的统一名称 */
    private static final String TOOL_NAME = "skill";

    @Autowired
    private SkillRepository repository;

    @Autowired
    private ApplicationContext applicationContext;

    /** 已加载的 Skill */
    private final Map<String, Skill> skillRegistry = new ConcurrentHashMap<>();

    /** 可执行技能的独立 ToolCallback */
    private final Map<String, ToolCallback> executableCallbacks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
        log.info("SkillsTool 初始化完成 — 技能: {}, 可执行工具: {}", skillRegistry.size(), executableCallbacks.size());
    }

    // ==================== 主 ToolCallback ====================

    /**
     * 获取 "skill" 这个统一的 ToolCallback，注册到 ChatClient。
     */
    public ToolCallback getToolCallback() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(TOOL_NAME)
                        .description(buildToolDescription())
                        .inputSchema("""
                                {"type":"object","properties":{"command":{"type":"string","description":"Skill name to invoke"}},"required":["command"]}
                                """)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return invokeSkill(toolInput);
            }
        };
    }

    /**
     * 获取所有可执行技能的独立 ToolCallback（FUNCTION 类型）。
     */
    public ToolCallback[] getSkillToolCallbacks() {
        return executableCallbacks.values().toArray(ToolCallback[]::new);
    }

    /**
     * 获取所有已加载的 ToolCallback（含主 skill 工具 + 可执行技能工具）。
     */
    public ToolCallback[] getAllToolCallbacks() {
        List<ToolCallback> all = new ArrayList<>();
        all.add(getToolCallback());
        all.addAll(executableCallbacks.values());
        return all.toArray(ToolCallback[]::new);
    }

    // ==================== 刷新 ====================

    /**
     * 刷新技能注册表 — 重新扫描文件系统。
     */
    public synchronized void refresh() {
        Map<String, Skill> nextRegistry = new ConcurrentHashMap<>();
        Map<String, ToolCallback> nextExecutables = new ConcurrentHashMap<>();

        List<Skill> skills = repository.listEnabled();
        for (Skill skill : skills) {
            if (skill.getName() == null || skill.getName().isBlank()) continue;
            nextRegistry.put(skill.getName(), skill);

            if (skill.getType() != null) {
                try {
                    ToolCallback cb = buildExecutableCallback(skill);
                    if (cb != null) {
                        nextExecutables.put(skill.getName(), cb);
                    }
                } catch (Exception e) {
                    log.error("技能 '{}' 可执行工具构建失败: {}", skill.getName(), e.getMessage());
                }
            }
        }

        skillRegistry.clear();
        skillRegistry.putAll(nextRegistry);
        executableCallbacks.clear();
        executableCallbacks.putAll(nextExecutables);
        log.info("SkillsTool 刷新完成 — 技能: {}, 可执行工具: {}", skillRegistry.size(), executableCallbacks.size());
    }

    /**
     * 获取技能总数。
     */
    public int getSkillCount() {
        return skillRegistry.size();
    }

    // ==================== 内部方法 ====================

    /**
     * 构建工具描述，列出所有可用技能（供 AI 语义匹配）。
     */
    private String buildToolDescription() {
        if (skillRegistry.isEmpty()) {
            return "Load and execute a skill by name. No skills are currently available.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Load and execute a skill by name. Available skills:\n\n");
        for (Skill skill : skillRegistry.values()) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null) {
                sb.append(": ").append(skill.getDescription());
            }
            if (skill.getType() != null) {
                sb.append(" [").append(skill.getType().name().toLowerCase()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 执行技能调用 — 加载完整 SKILL.md 内容并返回给 AI。
     */
    @SuppressWarnings("unchecked")
    private String invokeSkill(String toolInput) {
        try {
            JSONObject input = JSON.parseObject(toolInput);
            String command = input.getString("command");
            if (command == null || command.isBlank()) {
                return "{\"error\":\"Missing 'command' field — specify the skill name to invoke.\"}";
            }

            Skill skill = skillRegistry.get(command);
            if (skill == null) {
                // 重新从磁盘加载（可能新增）
                skill = repository.getByName(command);
                if (skill == null || !skill.isEnabled()) {
                    return "{\"error\":\"Skill not found: " + command + "\"}";
                }
                skillRegistry.put(command, skill);
            }

            // 返回完整 SKILL.md 内容 = frontmatter summary + body
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("skill", skill.getName());
            response.put("description", skill.getDescription());
            response.put("version", skill.getVersion());
            if (skill.getType() != null) {
                response.put("type", skill.getType().name().toLowerCase());
            }

            if (skill.getBody() != null && !skill.getBody().isBlank()) {
                response.put("content", skill.getBody());
            }

            // 如果是 FUNCTION 类型，告知 AI 有对应的可执行工具
            if (skill.getType() != null && skill.getEntry() != null) {
                response.put("entry", skill.getEntry());
                if (skill.getParameters() != null && !skill.getParameters().isEmpty()) {
                    response.put("parameters", skill.getParameters());
                }
                response.put("instruction", "This skill has an executable binding. "
                        + "You can invoke the corresponding tool: skill_" + skill.getName());
            }

            return JSON.toJSONString(response);
        } catch (Exception e) {
            log.error("技能调用失败: {}", toolInput, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ==================== 可执行技能 ====================

    private ToolCallback buildExecutableCallback(Skill skill) {
        return switch (skill.getType()) {
            case FUNCTION -> buildFunctionCallback(skill);
            case MCP -> null; // MCP 由 McpRegistry 管理
            case TOOL -> buildScriptCallback(skill);
        };
    }

    private ToolCallback buildFunctionCallback(Skill skill) {
        if (skill.getEntry() == null || !skill.getEntry().contains(".")) {
            log.warn("FUNCTION 技能 '{}' entry 格式无效: {}", skill.getName(), skill.getEntry());
            return null;
        }
        String[] parts = skill.getEntry().split("\\.", 2);
        String beanName = parts[0];
        String methodName = parts[1];

        Object bean;
        try {
            bean = applicationContext.getBean(beanName);
        } catch (Exception e) {
            log.warn("FUNCTION 技能 '{}' Bean 不存在: {}", skill.getName(), beanName);
            return null;
        }

        String toolName = "skill_" + skill.getName();
        String inputSchema = buildInputSchema(skill);

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(toolName)
                        .description(skill.getDescription())
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                try {
                    Map<String, Object> params = toolInput.isEmpty()
                            ? Map.of()
                            : JSON.parseObject(toolInput);
                    Method method = findBestMethod(bean.getClass(), methodName, params);
                    if (method == null) {
                        return "{\"error\":\"Method not found: " + methodName + "\"}";
                    }
                    method.setAccessible(true);
                    Object[] args = resolveArgs(method, params, skill);
                    Object result = method.invoke(
                            Modifier.isStatic(method.getModifiers()) ? null : bean, args);
                    return result instanceof String ? (String) result : JSON.toJSONString(result);
                } catch (Exception e) {
                    log.error("Skill 函数执行失败: {}", skill.getEntry(), e);
                    return "{\"error\":\"" + e.getMessage() + "\"}";
                }
            }
        };
    }

    private ToolCallback buildScriptCallback(Skill skill) {
        String toolName = "skill_" + skill.getName();
        String inputSchema = buildInputSchema(skill);

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(toolName)
                        .description(skill.getDescription())
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(skill.getEntry(), toolInput);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    String output = new String(process.getInputStream().readAllBytes());
                    process.waitFor();
                    return output;
                } catch (Exception e) {
                    log.error("脚本执行失败: {}", skill.getEntry(), e);
                    return "{\"error\":\"" + e.getMessage() + "\"}";
                }
            }
        };
    }

    private String buildInputSchema(Skill skill) {
        if (skill.getParameters() == null || skill.getParameters().isEmpty()) {
            return """
                    {"type":"object","properties":{},"required":[]}""";
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (SkillParameter p : skill.getParameters()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", mapType(p.getType()));
            prop.put("description", p.getDescription());
            props.put(p.getName(), prop);
            if (p.isRequired()) required.add(p.getName());
        }
        schema.put("properties", props);
        schema.put("required", required);
        return JSON.toJSONString(schema);
    }

    private String mapType(String type) {
        return switch (type.toLowerCase()) {
            case "int", "integer" -> "integer";
            case "number", "float", "double" -> "number";
            case "bool", "boolean" -> "boolean";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    private Method findBestMethod(Class<?> clazz, String methodName, Map<String, Object> params) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == params.size()) {
                return m;
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) return m;
        }
        return null;
    }

    private Object[] resolveArgs(Method method, Map<String, Object> params, Skill skill) {
        java.lang.reflect.Parameter[] mp = method.getParameters();
        Object[] args = new Object[mp.length];
        for (int i = 0; i < mp.length; i++) {
            String paramName = i < skill.getParameters().size()
                    ? skill.getParameters().get(i).getName()
                    : mp[i].getName();
            Object value = params.get(paramName);
            if (value != null && !mp[i].getType().isInstance(value)) {
                value = JSON.to(mp[i].getType(), value);
            }
            args[i] = value;
        }
        return args;
    }
}
