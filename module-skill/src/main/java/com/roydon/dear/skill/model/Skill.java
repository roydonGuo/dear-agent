package com.roydon.dear.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill 完整定义 = YAML frontmatter + Markdown body
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    // ---- frontmatter 字段 ----

    private String name;
    private String description;
    @Builder.Default
    private String version = "1.0.0";
    private String author;
    private SkillType type;
    private String entry;
    @Builder.Default
    private List<SkillParameter> parameters = new ArrayList<>();
    @Builder.Default
    private boolean enabled = true;

    // ---- 元数据 ----

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ---- Markdown body ----

    /** SKILL.md 中 YAML frontmatter 之后的 Markdown 正文 */
    private String body;

    /**
     * 兼容旧代码 — name 即 id
     */
    public String getId() {
        return name;
    }
}
