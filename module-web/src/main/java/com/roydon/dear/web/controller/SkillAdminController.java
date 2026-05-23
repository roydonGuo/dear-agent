package com.roydon.dear.web.controller;

import com.roydon.dear.common.BaseResult;
import io.micrometer.core.annotation.Timed;
import com.roydon.dear.skill.model.Skill;
import com.roydon.dear.skill.service.SkillService;
import com.roydon.dear.skill.tool.SkillsTool;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Skill 管理 REST API
 *
 * <p>存储格式：SKILL.md（YAML frontmatter + Markdown body），
 * 存放于 ~/.dear-agent/.skills/{name}/SKILL.md
 */
@Slf4j
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
public class SkillAdminController {

    private final SkillService skillService;
    private final SkillsTool skillsTool;

    @Timed(value = "skill.list", description = "List skills")
    @GetMapping
    @Operation(summary = "获取 Skill 列表")
    public BaseResult<List<Skill>> list() {
        return BaseResult.newSuccess(skillService.listAll());
    }

    @Timed(value = "skill.detail", description = "Get skill detail")
    @GetMapping("/{name}")
    @Operation(summary = "获取单个 Skill（含完整 Markdown body）")
    public BaseResult<Skill> detail(@PathVariable String name) {
        Skill skill = skillService.getByName(name);
        if (skill == null) {
            return BaseResult.newError("Skill 不存在: " + name);
        }
        return BaseResult.newSuccess(skill);
    }

    @Timed(value = "skill.create", description = "Create skill")
    @PostMapping
    @Operation(summary = "新增 Skill（自动生成 SKILL.md）")
    public BaseResult<Skill> create(@RequestBody Skill skill) {
        try {
            return BaseResult.newSuccess(skillService.create(skill));
        } catch (IllegalArgumentException e) {
            return BaseResult.newError(e.getMessage());
        } catch (Exception e) {
            log.error("创建 Skill 失败", e);
            return BaseResult.newError("创建失败: " + e.getMessage());
        }
    }

    @Timed(value = "skill.update", description = "Update skill")
    @PutMapping("/{name}")
    @Operation(summary = "更新 Skill（重写 SKILL.md）")
    public BaseResult<Skill> update(@PathVariable String name, @RequestBody Skill skill) {
        try {
            return BaseResult.newSuccess(skillService.update(name, skill));
        } catch (IllegalArgumentException e) {
            return BaseResult.newError(e.getMessage());
        } catch (Exception e) {
            log.error("更新 Skill 失败: {}", name, e);
            return BaseResult.newError("更新失败: " + e.getMessage());
        }
    }

    @Timed(value = "skill.delete", description = "Delete skill")
    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Skill（删除整个目录）")
    public BaseResult<Void> delete(@PathVariable String name) {
        Skill skill = skillService.getByName(name);
        if (skill == null) {
            return BaseResult.newError("Skill 不存在: " + name);
        }
        boolean result = skillService.delete(name);
        return result ? BaseResult.newSuccess() : BaseResult.newError("删除失败");
    }

    @Timed(value = "skill.toggle", description = "Toggle skill enabled")
    @PatchMapping("/{name}/toggle")
    @Operation(summary = "启用 / 禁用 Skill")
    public BaseResult<Skill> toggle(@PathVariable String name) {
        try {
            return BaseResult.newSuccess(skillService.toggleEnabled(name));
        } catch (IllegalArgumentException e) {
            return BaseResult.newError(e.getMessage());
        } catch (Exception e) {
            log.error("切换 Skill 状态失败: {}", name, e);
            return BaseResult.newError("操作失败: " + e.getMessage());
        }
    }

    @Timed(value = "skill.refresh", description = "Refresh skill registry")
    @PostMapping("/refresh")
    @Operation(summary = "刷新 SkillsTool 注册表")
    public BaseResult<Map<String, Object>> refresh() {
        skillsTool.refresh();
        return BaseResult.newSuccess(Map.of(
                "skills", skillsTool.getSkillCount(),
                "tools", skillsTool.getSkillToolCallbacks().length
        ));
    }

    @Timed(value = "skill.tools", description = "List skill tools")
    @GetMapping("/tools")
    @Operation(summary = "获取已加载的工具列表（含主 skill 工具 + 可执行技能工具）")
    public BaseResult<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback tc : skillsTool.getAllToolCallbacks()) {
            ToolDefinition def = tc.getToolDefinition();
            tools.add(Map.of("name", def.name(), "description", def.description()));
        }
        return BaseResult.newSuccess(tools);
    }
}
