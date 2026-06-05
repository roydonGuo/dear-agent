package com.roydon.dear.agent.registry;

public interface AgentMetadata {

    /** Agent 唯一标识，用于注册和路由 */
    String agentName();

    /** Agent 功能描述，注入 Tool 的 description，供 LLM 选择调用 */
    String description();

    /** Agent 角色类型：chat / search / plan / execute / critique */
    String role();

    /**
     * 同步调用入口。
     * 传入任务描述，返回执行结果字符串。
     * Phase 1 中 Agent 作为 Tool 被调用时走此方法。
     */
    String callSync(String input);
}
