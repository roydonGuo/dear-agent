package com.roydon.dear.skill.model;

/**
 * Skill 类型枚举
 */
public enum SkillType {

    /** 本地函数：由 Spring Bean 方法提供实现 */
    FUNCTION,

    /** MCP 远程工具：通过 MCP 协议连接到外部服务 */
    MCP,

    /** 通用工具：脚本或外部程序 */
    TOOL
}
