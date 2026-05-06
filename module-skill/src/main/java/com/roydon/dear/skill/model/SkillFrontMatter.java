package com.roydon.dear.skill.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SKILL.md 的 YAML frontmatter 部分
 */
@Data
public class SkillFrontMatter {

    /** 技能名称（同时作为目录名和唯一标识） */
    private String name;

    /** 技能描述（用于语义匹配，建议包含触发关键词） */
    private String description;

    /** 版本号 */
    private String version = "1.0.0";

    /** 作者 */
    private String author;

    /** 技能类型：function / mcp / tool */
    private SkillType type;

    /** 入口定义：function → bean.method, mcp → server:tool, tool → script */
    private String entry;

    /** 参数列表 */
    private List<SkillParameter> parameters = new ArrayList<>();

    /** 是否启用 */
    private boolean enabled = true;
}
