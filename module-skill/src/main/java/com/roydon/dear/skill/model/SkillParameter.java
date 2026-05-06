package com.roydon.dear.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 参数定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillParameter {

    /** 参数名 */
    private String name;

    /** 参数类型：string / int / number / boolean / object / array */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 参数说明 */
    private String description;

    /** 默认值（可选） */
    private String defaultValue;
}
