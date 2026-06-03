# 多 Agent 协同系统 — 实现方案

> 基于 dear-agent v1.0-SNAPSHOT 架构评估，Spring Boot 3.5.6 + Spring AI 1.1.7

---

## 目录

1. [现状评估](#1-现状评估)
2. [总体架构设计](#2-总体架构设计)
3. [Phase 1：Agent-as-Tool 基础框架](#3-phase-1agent-as-tool-基础框架)
4. [Phase 2：Plan-Execute-Critique 闭环](#4-phase-2plan-execute-critique-闭环)
5. [Phase 3：Supervisor-Worker 完全体](#5-phase-3supervisor-worker-完全体)
6. [数据模型变更](#6-数据模型变更)
7. [API & SSE 协议扩展](#7-api--sse-协议扩展)
8. [风险与对策](#8-风险与对策)

---

## 1. 现状评估

### 1.1 可直接复用的能力

| 能力 | 位置 | 复用方式 |
|------|------|----------|
| `BaseAgent` 抽象基类 | `module-agent` | 所有 Agent 继承，获得 ChatMemory / TaskManager / SSE 格式化 |
| `AgentTaskManager` 分布式锁 | `module-common` | Sub-Agent 生命周期管理，Redis Pub/Sub 跨实例启停 |
| `McpToolManager` 工具聚合 | `module-tool` | Agent 注册为 ToolCallback，实现 Agent-as-Tool |
| `PlanExecutePrompts` | `module-common` | 五阶段提示词（Plan/Execute/Critique/Compress/Summarize） |
| `ModelRegistry` 多模型 | `module-model` | Planner 用强模型，Executor 用轻模型 |
| SSE type-tagged 协议 | `AgentResponse` | 新增 `agent_start`/`agent_done`/`agent_error` 类型 |
| `DearAgent` 轮次流式模式 | `module-agent` | 编排 Agent 的核心参考实现 |
| 会话持久化 | `module-session` | ChatConversation + ChatMessage 双表，复用 |

### 1.2 需要新建的能力

- **Agent 注册与发现** — 当前硬编码构建 DearAgent，无路由机制
- **编排控制层** — 无 Orchestrator 抽象，无法管理 Sub-Agent 协作
- **Agent 间上下文传递** — 无共享上下文或消息总线
- **层级任务管理** — 当前 `agent:task:{conversationId}` key 假设单任务，需层级化
- **Sub-Agent 流式事件类型** — SSE 协议需区分不同 Agent 的输出
- **多 Agent 独立 Controller** — 新增 `MultiAgentController`，在 Web 层与原有 `AgentController` 隔离

---

## 2. 总体架构设计

### 2.1 目标架构

```
   ┌──────────────────────────────────────────────────┐
   │                   Web 层                          │
   │                                                   │
   │  ┌─────────────────────┐  ┌─────────────────────┐ │
   │  │  AgentController     │  │ MultiAgentController │ │
   │  │  (保留，单 Agent)     │  │ (新增，多 Agent 协同) │ │
   │  │  GET /agent/chat/    │  │ POST /multi-agent/   │ │
   │  │       stream         │  │      collaborate/    │ │
   │  └─────────┬───────────┘  │      stream           │ │
   │            │              └──────────┬──────────┘ │
   └────────────┼─────────────────────────┼────────────┘
                │                         │
       ┌────────▼────────┐     ┌──────────▼───────────┐
       │   DearAgent      │     │   OrchestratorAgent   │
       │   (简单对话)       │     │   extends BaseAgent   │
       │   extends        │     │                       │
       │   BaseAgent      │     │   ┌─────────────────┐ │
       └──────────────────┘     │   │  AgentRegistry   │ │
                                │   │  (Spring Bean)   │ │
                                │   └────────┬────────┘ │
                                │   ┌────────▼────────┐ │
                                │   │ AgentToolAdapter │ │
                                │   └────────┬────────┘ │
                                └────────────┼──────────┘
                                             │
              ┌──────────────────────────────┼──────────────────┐
              │                              │                  │
     ┌────────▼────────┐  ┌─────────────────▼──┐  ┌────────────▼───┐
     │ WebSearchAgent   │  │  PlanExecute       │  │  WorkerAgent    │
     │ (联网搜索)        │  │  Orchestrator      │  │  (独立执行)      │
     │ extends          │  │  (Phase 2)         │  │  (Phase 3)      │
     │ BaseAgent        │  │                    │  │                 │
     └──────────────────┘  └────────────────────┘  └─────────────────┘
```

**两条独立的调用路径：**

| 路径 | Controller | Agent | 场景 |
|------|-----------|-------|------|
| 简单对话 | `AgentController` → `DearAgent` | 单 Agent，直接回答 | 日常聊天、简单问答、文件操作 |
| 多 Agent 协同 | `MultiAgentController` → `OrchestratorAgent` | 编排器调度 Sub-Agent | 深度研究、复杂多步骤任务 |

### 2.2 三层演进路线

```
Phase 1 ──── Agent-as-Tool ────▶  Phase 2 ──── Plan-Execute ────▶  Phase 3
(基础框架)                      (深度研究)                        (完全自主)
2 周                            2-3 周                            按需
```

### 2.3 新增/变更模块

```
module-web/
├── controller/
│   ├── AgentController.java            # 已有，不动 — 单 Agent 对话
│   └── MultiAgentController.java       # 新增 — 多 Agent 协同入口

module-agent/
├── BaseAgent.java                    # 已有，增加 agentRole/capabilities 字段
├── DearAgent.java                    # 已有，不变
├── WebSearchReactAgent.java          # 已有，实现 AgentMetadata 接口
├── ReactAgent.java                   # 已有，不变
│
├── registry/                         # 新增：Agent 注册与发现
│   ├── AgentRegistry.java            #   Agent 注册中心（Spring Bean）
│   ├── AgentMetadata.java            #   Agent 元信息接口
│   └── AgentToolAdapter.java         #   Agent → ToolCallback 适配器
│
├── orchestrator/                     # 新增：编排层
│   ├── AgentOrchestrator.java        #   编排器抽象接口
│   ├── SimpleOrchestrator.java       #   Phase 1：Agent-as-Tool 编排
│   ├── PlanExecuteOrchestrator.java  #   Phase 2：Plan-Execute 编排
│   └── OrchestrationContext.java     #   编排上下文（共享状态）
│
└── worker/                           # 新增：Worker Agent（Phase 3）
    ├── WorkerAgent.java              #   独立 Worker Agent
    └── WorkerPool.java               #   Worker 池管理
```

---

## 3. Phase 1：Agent-as-Tool 基础框架

### 3.1 目标

- 建立 Agent 注册/发现机制
- 子 Agent 可通过 Tool Calling 被编排 Agent 调度
- 前端无感 — SSE 协议向后兼容
- `AgentController` 和 `DearAgent` **零改动**，现有简单对话路径完全不受影响
- 多 Agent 功能通过新增 `MultiAgentController` 独立暴露

### 3.2 Agent 元信息接口

```java
// module-agent/src/main/java/.../registry/AgentMetadata.java

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
```

### 3.3 Agent 注册中心

```java
// module-agent/src/main/java/.../registry/AgentRegistry.java

package com.roydon.dear.agent.registry;

import org.springframework.ai.tool.ToolCallback;
import java.util.*;

@Component
public class AgentRegistry {

    private final Map<String, BaseAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentMetadata> metadata = new ConcurrentHashMap<>();

    /**
     * 注册一个 Agent。
     * 通常在 Agent 的 @PostConstruct 或 Builder 中调用。
     */
    public void register(BaseAgent agent, AgentMetadata meta) {
        agents.put(meta.agentName(), agent);
        metadata.put(meta.agentName(), meta);
    }

    public BaseAgent getAgent(String name) {
        BaseAgent agent = agents.get(name);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + name);
        return agent;
    }

    public Set<String> getAgentNames() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    public AgentMetadata getMetadata(String name) {
        return metadata.get(name);
    }

    /**
     * 将所有已注册 Agent 导出为 ToolCallback 列表，
     * 可直接注入 ChatClient 的 toolCallbacks。
     */
    public List<ToolCallback> getAgentTools() {
        return agents.entrySet().stream()
                .map(e -> new AgentToolAdapter(e.getValue(), metadata.get(e.getKey())))
                .map(ToolCallback.class::cast)
                .toList();
    }

    /**
     * 按 role 过滤 Agent 工具
     */
    public List<ToolCallback> getAgentToolsByRole(String role) {
        return agents.entrySet().stream()
                .filter(e -> role.equals(metadata.get(e.getKey()).role()))
                .map(e -> new AgentToolAdapter(e.getValue(), metadata.get(e.getKey())))
                .map(ToolCallback.class::cast)
                .toList();
    }
}
```

### 3.4 Agent → Tool 适配器

```java
// module-agent/src/main/java/.../registry/AgentToolAdapter.java

package com.roydon.dear.agent.registry;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolCallResult;
import org.springframework.ai.tool.execution.ToolExecutionException;

public class AgentToolAdapter implements ToolCallback {

    private final BaseAgent agent;
    private final AgentMetadata metadata;

    public AgentToolAdapter(BaseAgent agent, AgentMetadata metadata) {
        this.agent = agent;
        this.metadata = metadata;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(metadata.agentName())
                .description(metadata.description() + "\n输入格式：直接传入你需要该 Agent 完成的任务描述文本。")
                .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "task": {
                          "type": "string",
                          "description": "需要该 Agent 完成的任务描述"
                        }
                      },
                      "required": ["task"]
                    }
                    """)
                .build();
    }

    @Override
    public ToolCallResult call(String toolInput) {
        try {
            String result = metadata.callSync(toolInput);
            return new SuccessfulToolCallResult(result);
        } catch (Exception e) {
            throw new ToolExecutionException("Agent [" + metadata.agentName() + "] 执行失败: " + e.getMessage());
        }
    }
}
```

### 3.5 编排 Agent（Phase 1 版本）

```java
// module-agent/src/main/java/.../orchestrator/SimpleOrchestrator.java

package com.roydon.dear.agent.orchestrator;

import com.roydon.dear.agent.BaseAgent;
import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.agent.registry.AgentRegistry;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/**
 * Phase 1 编排器：继承 DearAgent 的全部能力，
 * 在其工具列表中注入已注册的 Agent 工具。
 *
 * 对 LLM 来说，调用一个 Sub-Agent 和调用普通 Tool 没有区别 —
 * 但 AgentToolAdapter 内部会走完整的 BaseAgent 执行流程。
 */
public class SimpleOrchestrator extends DearAgent {

    private final AgentRegistry agentRegistry;

    public SimpleOrchestrator(Builder builder, AgentRegistry agentRegistry) {
        // 合并普通工具 + Agent 工具
        super(builder.name, builder.chatModel,
              mergeTools(builder.tools, agentRegistry),
              builder.systemPrompt, builder.maxRounds,
              builder.chatMemory, builder.advisors, builder.maxReflectionRounds,
              builder.conversationService, builder.messageService,
              builder.taskManager, builder.knowledgeRetrievalService);
        this.agentRegistry = agentRegistry;
    }

    private static List<ToolCallback> mergeTools(
            List<ToolCallback> normalTools, AgentRegistry registry) {
        List<ToolCallback> all = new ArrayList<>(normalTools);
        all.addAll(registry.getAgentTools());
        return all;
    }

    /**
     * 覆盖工具状态 SSE 发射 — Sub-Agent 调用发射 agent_start/agent_done
     */
    @Override
    protected String emitToolStatus(String toolType, String content) {
        // 判断是否为 Agent 工具
        if (agentRegistry.getAgentNames().contains(extractToolName(content))) {
            return emitAgentStatus(content);
        }
        return super.emitToolStatus(toolType, content);
    }

    private String emitAgentStatus(String content) {
        // 复用 AgentResponse 的 SSE 格式, type = "agent_call"
        return AgentResponse.json("agent_call", content);
    }
}
```

### 3.6 让 WebSearchReactAgent 实现 AgentMetadata

```java
// 在 WebSearchReactAgent 中添加
public class WebSearchReactAgent extends BaseAgent implements AgentMetadata {

    @Override
    public String agentName() { return "web_search_agent"; }

    @Override
    public String description() {
        return "联网搜索专家，擅长搜索最新信息、验证事实、查找资料。";
    }

    @Override
    public String role() { return "search"; }

    @Override
    public String callSync(String input) {
        // 同步调用：收集 Flux 结果拼接为字符串
        StringBuilder sb = new StringBuilder();
        execute(UUID.randomUUID().toString(), input)
                .doOnNext(chunk -> {
                    JSONObject json = JSON.parseObject(chunk);
                    if ("text".equals(json.getString("type"))) {
                        sb.append(json.getString("content"));
                    }
                })
                .blockLast();
        return sb.toString();
    }
}
```

### 3.7 新增 MultiAgentController

`AgentController` **保持原样不动**，继续使用 `DearAgent` 处理简单单 Agent 对话场景。多 Agent 协同场景走全新的 `MultiAgentController`。

```java
// module-web/src/main/java/.../controller/MultiAgentController.java

package com.roydon.dear.web.controller;

import com.roydon.dear.agent.DearAgent;
import com.roydon.dear.agent.orchestrator.SimpleOrchestrator;
import com.roydon.dear.agent.registry.AgentRegistry;
import com.roydon.dear.common.AgentResponse;
import com.roydon.dear.common.manager.AgentTaskManager;
import com.roydon.dear.common.prompts.ReactAgentPrompts;
import com.roydon.dear.knowledge.rag.retriever.KnowledgeRetrievalService;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.model.tts.AgentVoiceStreamService;
import com.roydon.dear.prompt.service.AiPromptService;
import com.roydon.dear.session.service.ChatConversationService;
import com.roydon.dear.session.service.ChatMessageService;
import com.roydon.dear.session.service.IAiChatFileService;
import com.roydon.dear.tool.McpToolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/multi-agent")
public class MultiAgentController {

    @Autowired
    private ModelRegistry modelRegistry;
    @Autowired
    private ChatConversationService conversationService;
    @Autowired
    private ChatMessageService messageService;
    @Autowired
    private AiPromptService aiPromptService;
    @Autowired
    private AgentTaskManager taskManager;
    @Autowired
    private AgentVoiceStreamService agentVoiceStreamService;
    @Autowired
    private McpToolManager mcpToolManager;
    @Autowired
    private IAiChatFileService aiChatFileService;
    @Autowired
    private KnowledgeRetrievalService knowledgeRetrievalService;
    @Autowired
    private AgentRegistry agentRegistry;

    /**
     * 多 Agent 协同流式接口
     *
     * @param query          用户查询内容
     * @param conversationId 会话ID
     * @param mode           协作模式：auto(LLM自动调度) | research(深度研究PlanExecute)
     * @param think          是否启用深度思考
     * @param fileIds        关联文件ID列表
     */
    @GetMapping(value = "/collaborate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> collaborateStream(@RequestParam String query,
                                          @RequestParam String conversationId,
                                          @RequestParam(defaultValue = "auto") String mode,
                                          @RequestParam(required = false) Boolean think,
                                          @RequestParam(required = false) String fileIds) {
        log.info("多Agent协同请求: query={}, conversationId={}, mode={}", query, conversationId, mode);

        try {
            SimpleOrchestrator orchestrator = buildOrchestrator(conversationId, fileIds);
            return orchestrator.stream(conversationId, query, Boolean.TRUE.equals(think), fileIds, null, null);
        } catch (Exception e) {
            log.error("多Agent协同异常", e);
            return Flux.just(
                    AgentResponse.error("多Agent协同异常：" + e.getMessage()),
                    AgentResponse.done("error"));
        }
    }

    /**
     * 停止多 Agent 协同执行
     */
    @GetMapping("/stop")
    public Map<String, Object> stopCollaborate(@RequestParam String conversationId) {
        log.info("停止多Agent协同: conversationId={}", conversationId);
        boolean success = taskManager.stopTask(conversationId);
        return Map.of("code", 200, "success", success,
                "message", success ? "已停止执行" : "没有找到正在执行的任务");
    }

    // ===== 编排器构建（与 AgentController.initDearAgent 平行，互不影响） =====

    private SimpleOrchestrator buildOrchestrator(String conversationId, String fileIds) {
        // 系统提示词加载逻辑与 AgentController 一致
        String systemPrompt = loadSystemPrompt(conversationId, fileIds);
        ToolCallback[] tools = mcpToolManager.getAllTools();
        ChatModel chatModel = modelRegistry.getDefaultChatModel("chat");
        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(System.getProperty("user.home") + "/.dear-agent/.skills")
                .build();
        SpringAiSkillAdvisor skillAdvisor = SpringAiSkillAdvisor.builder()
                .skillRegistry(skillRegistry)
                .build();

        SimpleOrchestrator orchestrator = SimpleOrchestrator.builder()
                .name("multi-agent-orchestrator")
                .chatModel(chatModel)
                .tools(tools)
                .advisors(skillAdvisor)
                .systemPrompt(enhanceSystemPromptForMultiAgent(systemPrompt))
                .conversationService(conversationService)
                .messageService(messageService)
                .taskManager(taskManager)
                .knowledgeRetrievalService(knowledgeRetrievalService)
                .maxRounds(50)
                .agentRegistry(agentRegistry)
                .build();

        if (StringUtils.isNotBlank(conversationId)) {
            ChatMemory chatMemory = orchestrator.createPersistentChatMemory(conversationId, 30);
            orchestrator.setChatMemory(chatMemory);
        }
        return orchestrator;
    }

    /**
     * 在多 Agent 场景下增强系统提示词，告知 LLM 可调度的 Agent 信息。
     */
    private String enhanceSystemPromptForMultiAgent(String basePrompt) {
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n## 可调度的专业 Agent\n");
        sb.append("你可以调用以下专业 Agent 来协作完成任务：\n");
        for (String name : agentRegistry.getAgentNames()) {
            var meta = agentRegistry.getMetadata(name);
            sb.append("- **").append(name).append("**: ").append(meta.description()).append("\n");
        }
        sb.append("\n根据任务复杂度自主决定是否调度 Agent。简单任务可直接回答。\n");
        return sb.toString();
    }

    // loadSystemPrompt 复用与 AgentController 相同的逻辑
}
```

**与 AgentController 的职责划分：**

| Controller | 路径前缀 | 构建的 Agent | 适用场景 |
|------------|---------|-------------|----------|
| `AgentController` | `/agent` | `DearAgent` | 简单对话、日常问答、单轮工具调用 |
| `MultiAgentController` | `/multi-agent` | `SimpleOrchestrator` | 复杂任务、需多 Agent 协作、深度研究 |

两个 Controller 互不依赖、平行存在，`AgentController` 原有逻辑一行不改。

### 3.8 Agent 自动注册

```java
// module-agent 中新增 AutoConfiguration

@Configuration
public class AgentAutoConfiguration {

    @Bean
    public AgentRegistry agentRegistry() {
        return new AgentRegistry();
    }

    /**
     * 注册内置 Agent 到注册中心。
     * 新增 Agent 只需在此处添加一行 register 调用。
     */
    @Bean
    public Object registerBuiltinAgents(AgentRegistry registry,
                                         ChatModel chatModel,
                                         ChatConversationService convService,
                                         ChatMessageService msgService,
                                         AgentTaskManager taskManager) {
        // 注册 WebSearchReactAgent
        WebSearchReactAgent searchAgent = WebSearchReactAgent.builder()
                .name("web-search-agent")
                .chatModel(chatModel)
                .conversationService(convService)
                .messageService(msgService)
                .taskManager(taskManager)
                .build();
        registry.register(searchAgent, searchAgent); // 自身即 AgentMetadata

        // 后续可在此注册更多 Agent:
        // registry.register(codeAgent, codeAgent);
        // registry.register(dataAgent, dataAgent);

        return new Object(); // 占位 Bean
    }
}
```

### 3.9 Phase 1 小结

| 项目 | 改动 |
|------|------|
| 新增文件 | `AgentMetadata`, `AgentRegistry`, `AgentToolAdapter`, `SimpleOrchestrator`, `AgentAutoConfiguration`, `MultiAgentController` |
| 修改文件 | `WebSearchReactAgent` 实现 `AgentMetadata` |
| 不动文件 | `AgentController`, `DearAgent`, `BaseAgent`, `ReactAgent`, `AgentTaskManager`, 全部 Tool 类 |
| 前端影响 | 无。多 Agent 走新路径 `/multi-agent/collaborate/stream`，单 Agent 走旧路径 `/agent/chat/stream` 不变 |

---

## 4. Phase 2：Plan-Execute-Critique 闭环

### 4.1 目标

- 实现深度研究类任务的自动规划-执行-评审循环
- 复用 `PlanExecutePrompts` 中已定义的五阶段提示词
- 支持 Plan → Execute → Critique → Compress → Summarize 完整流程
- 每个阶段使用不同的 System Prompt + 可选不同的 Model

### 4.2 Plan-Execute 流程

```
用户问题
    │
    ▼
┌─────────────────────────┐
│ REQUIREMENT_CLARIFICATION│  ← 判断需求是否明确
└───────────┬─────────────┘
            │ 明确
            ▼
┌─────────────────────────┐
│ RESEARCH_TOPIC_GENERATION│  ← 拆解为 N 个研究维度
└───────────┬─────────────┘
            │
            ▼
    ┌───────────────────────────────┐
    │        RESEARCH LOOP          │
    │                               │
    │  ┌───────────────────────┐    │
    │  │ PLAN    (规划专家)     │    │  ← 生成 tool calling 计划 (JSON)
    │  └─────────┬─────────────┘    │
    │            │ tasks[]          │
    │            ▼                  │
    │  ┌───────────────────────┐    │
    │  │ EXECUTE (执行专家)     │    │  ← 并行/串行执行工具，记录结果
    │  └─────────┬─────────────┘    │
    │            │ results          │
    │            ▼                  │
    │  ┌───────────────────────┐    │
    │  │ CRITIQUE (评审专家)     │    │  ← passed? Y→退出循环
    │  └─────────┬─────────────┘    │      N→COMPRESS→回到 PLAN
    │            │                  │
    │  ┌─────────▼─────────────┐    │
    │  │ COMPRESS (压缩专家)    │    │  ← 上下文超限时压缩
    │  └───────────────────────┘    │
    │                               │
    │  最大循环次数: 5               │
    └───────────────┬───────────────┘
                    │ passed = true
                    ▼
        ┌───────────────────────┐
        │ SUMMARIZE (总结专家)   │  ← 生成最终报告
        └───────────────────────┘
```

### 4.3 核心类：PlanExecuteOrchestrator

```java
// module-agent/src/main/java/.../orchestrator/PlanExecuteOrchestrator.java

package com.roydon.dear.agent.orchestrator;

import com.roydon.dear.agent.BaseAgent;
import com.roydon.dear.common.prompts.PlanExecutePrompts;
import com.roydon.dear.model.registry.ModelRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.*;

/**
 * Phase 2 编排器：Plan-Execute-Critique 深度研究流程。
 *
 * 关键设计决策：
 * - 每个阶段不是独立的 Agent 实例，而是同一 ChatClient 管道上切换 System Prompt 的角色
 * - 这样做避免了多个 Agent 实例的 ChatMemory/ChatClient 创建开销
 * - 各阶段共享同一个 messages 列表（上下文累积）
 */
public class PlanExecuteOrchestrator extends BaseAgent {

    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ChatModel lightModel;   // 轻量模型，用于 PLAN / CRITIQUE / COMPRESS
    private final int maxResearchLoops;   // 最大研究循环次数，建议 5

    public PlanExecuteOrchestrator(String name, ChatModel chatModel, ChatModel lightModel,
                                    List<ToolCallback> tools, int maxResearchLoops, ...) {
        super(name, chatModel, "plan_execute");
        this.tools = tools;
        this.lightModel = lightModel;
        this.maxResearchLoops = maxResearchLoops;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(tools)
                .build();
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        // ... 基础检查、任务注册等（复用 BaseAgent） ...

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Phase 0: 需求澄清
        String clarified = clarifyRequirement(question);

        // Phase 1: 研究主题拆解
        String topics = generateResearchTopics(clarified);

        // Phase 2: 研究循环
        List<SearchTask> plan = generatePlan(clarified, topics);
        StringBuilder researchContext = new StringBuilder();

        for (int loop = 0; loop < maxResearchLoops; loop++) {
            // Execute
            String executeResult = executePlan(plan, researchContext, sink);
            researchContext.append(executeResult);

            // Critique
            CritiqueResult critique = evaluateResearch(question, researchContext.toString());
            if (critique.passed()) {
                break;
            }
            // 未通过：压缩上下文 + 重新规划
            researchContext = new StringBuilder(compressContext(researchContext.toString(), critique));
            plan = generatePlan(clarified, topics, critique.feedback()); // 带上反馈
        }

        // Phase 3: 总结
        String report = summarize(question, researchContext.toString(), sink);
        sink.tryEmitNext(createDoneResponse(conversationId));
        sink.tryEmitComplete();

        return sink.asFlux();
    }

    // ===== 各阶段实现 =====

    /**
     * PLAN 阶段：调用轻量模型，返回 JSON 任务列表
     */
    private List<SearchTask> generatePlan(String question, String topics) {
        return generatePlan(question, topics, null);
    }

    private List<SearchTask> generatePlan(String question, String topics, String lastCritique) {
        String prompt = buildPlanPrompt(question, topics, lastCritique);
        String response = ChatClient.builder(lightModel != null ? lightModel : chatModel)
                .build().prompt()
                .system(PlanExecutePrompts.PLAN)
                .user(prompt)
                .call()
                .content();
        return parsePlanResponse(response);
    }

    /**
     * EXECUTE 阶段：执行计划中的工具调用
     */
    private String executePlan(List<SearchTask> plan, StringBuilder context, Sinks sink) {
        // 按 order 分组：同 order 的并行执行
        Map<Integer, List<SearchTask>> groups = plan.stream()
                .collect(Collectors.groupingBy(SearchTask::order));

        StringBuilder result = new StringBuilder();
        for (int order : groups.keySet().stream().sorted().toList()) {
            List<SearchTask> batch = groups.get(order);
            // 并行执行同 order 的任务（复用 DearAgent 的 parallel tool call 模式）
            // ... 调用 chatClient 执行工具 ...
        }
        return result.toString();
    }

    /**
     * CRITIQUE 阶段：评估研究是否充分
     */
    private CritiqueResult evaluateResearch(String question, String context) {
        String response = ChatClient.builder(lightModel != null ? lightModel : chatModel)
                .build().prompt()
                .system(PlanExecutePrompts.CRITIQUE)
                .user("用户问题：%s\n\n研究结果：\n%s".formatted(question, context))
                .call()
                .content();
        return parseCritiqueResponse(response); // 解析 { "passed": true/false, "feedback": "..." }
    }

    /**
     * COMPRESS 阶段：压缩上下文
     */
    private String compressContext(String context, CritiqueResult critique) {
        return ChatClient.builder(lightModel != null ? lightModel : chatModel)
                .build().prompt()
                .system(PlanExecutePrompts.COMPRESS)
                .user("原始上下文：\n\n%s\n\n最近评审：\n%s".formatted(context, critique.feedback()))
                .call()
                .content();
    }

    /**
     * SUMMARIZE 阶段：生成最终报告
     */
    private String summarize(String question, String context, Sinks.Many<String> sink) {
        // 流式输出最终报告
        return ChatClient.builder(chatModel).build().prompt()
                .system(PlanExecutePrompts.SUMMARIZE)
                .user("用户问题：%s\n\n研究结果：\n%s".formatted(question, context))
                .stream()
                .chatResponse()
                .doOnNext(chunk -> sink.tryEmitNext(createTextResponse(chunk.getResult().getOutput().getText())))
                .then().block();
    }
}

// 内部类型
record SearchTask(String id, String instruction, int order) {}
record CritiqueResult(boolean passed, String feedback) {}
```

### 4.4 编程式编排 API

Phase 2 额外提供一个编程式编排 API，让开发者可以用代码（而非 LLM 动态决策）编排固定的 Agent 协作流程：

```java
// module-agent/src/main/java/.../orchestrator/AgentOrchestrator.java

public interface AgentOrchestrator {
    /**
     * 链式编排：A → B → C，每个 Agent 的输出作为下一个的输入
     */
    Flux<String> chain(String conversationId, String input, String... agentNames);

    /**
     * 并行编排：同时执行多个 Agent，合并结果
     */
    Flux<String> parallel(String conversationId, String input, String... agentNames);

    /**
     * 条件编排：根据路由函数选择 Agent
     */
    Flux<String> route(String conversationId, String input,
                       Function<String, String> router, Map<String, String> agentMap);
}
```

```java
// 使用示例
@Component
public class ResearchPipeline {

    @Autowired
    private AgentOrchestrator orchestrator;

    public Flux<String> deepResearch(String conversationId, String question) {
        return orchestrator.chain(conversationId, question,
            "search_agent",    // 1. 搜索资料
            "analyze_agent",   // 2. 分析整理
            "summarize_agent"  // 3. 生成报告
        );
    }
}
```

### 4.5 Phase 2 小结

| 项目 | 改动 |
|------|------|
| 新增文件 | `PlanExecuteOrchestrator`, `AgentOrchestrator` 接口及实现, `SearchTask`, `ResearchPipeline` |
| 修改文件 | `AgentRegistry` 增加 `getAgentByRole()`；`MultiAgentController` 增加 `mode=research` 路由到 `PlanExecuteOrchestrator` |
| 不动文件 | `AgentController` 及所有已有 Agent、Tool |
| 前端影响 | 新增 `plan`、`critique` SSE type，旧 type 不变 |

---

## 5. Phase 3：Supervisor-Worker 完全体

### 5.1 目标

- 每个 Worker Agent 是独立的 `BaseAgent` 实例，拥有独立 ChatMemory
- Supervisor 可以并行启动/取消多个 Worker
- Worker 间通过共享 Blackboard 交换信息
- Worker 错误隔离：单个 Worker 失败不影响其他

### 5.2 WorkerAgent 设计

```java
// module-agent/src/main/java/.../worker/WorkerAgent.java

public class WorkerAgent extends BaseAgent {

    private final String workerId;
    private final String roleDescription;
    private final List<ToolCallback> assignedTools;
    private final ChatClient chatClient;
    private final OrchestrationContext sharedContext;  // 共享上下文

    private final AtomicReference<WorkerState> state =
            new AtomicReference<>(WorkerState.IDLE);

    @Override
    public Flux<String> execute(String conversationId, String task) {
        state.set(WorkerState.RUNNING);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 1. 从 sharedContext 读取前置 Worker 的输出
        String priorResults = sharedContext.readAll();

        // 2. 构建消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(roleDescription));
        if (priorResults != null && !priorResults.isEmpty()) {
            messages.add(new SystemMessage("其他 Worker 已完成的成果：\n" + priorResults));
        }
        messages.add(new UserMessage(task));

        // 3. 走标准 round-based 流式执行（类似 DearAgent）
        scheduleRound(messages, sink, /* ... */);

        return sink.asFlux()
                .doFinally(sig -> {
                    state.set(sig == SignalType.ON_COMPLETE
                            ? WorkerState.COMPLETED : WorkerState.FAILED);
                    // 将最终输出写入共享上下文
                    sharedContext.write(workerId, getFinalOutput());
                });
    }

    public WorkerState getState() { return state.get(); }
    public void cancel() { /* dispose subscription */ }
}
```

### 5.3 OrchestrationContext（Blackboard 模式）

```java
// module-agent/src/main/java/.../orchestrator/OrchestrationContext.java

/**
 * 共享上下文黑板：Worker Agent 之间通过它交换信息。
 * 线程安全，支持按 Agent 名称分组写入和全局读取。
 */
public class OrchestrationContext {

    private final Map<String, String> agentOutputs = new ConcurrentHashMap<>();
    private final List<String> eventLog = Collections.synchronizedList(new ArrayList<>());

    public void write(String agentName, String output) {
        agentOutputs.put(agentName, output);
        eventLog.add("[%s] %s: %s".formatted(
                LocalDateTime.now().toString(), agentName,
                output.length() > 200 ? output.substring(0, 200) + "..." : output));
    }

    public String read(String agentName) {
        return agentOutputs.get(agentName);
    }

    /** 读取所有 Agent 输出，用分隔符合并 */
    public String readAll() {
        return agentOutputs.entrySet().stream()
                .map(e -> "【%s】:\n%s".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    public List<String> getEventLog() { return new ArrayList<>(eventLog); }
}
```

### 5.4 SupervisorAgent

```java
// module-agent/src/main/java/.../worker/SupervisorAgent.java

/**
 * Phase 3 Supervisor：管理多个 WorkerAgent 的并行/串行执行。
 *
 * 与 Phase 1/2 的关键区别：
 * - 每个 Worker 是独立的 BaseAgent 实例（独立 ChatMemory、独立 ChatClient）
 * - Supervisor 不参与具体的 LLM 推理，专注于任务分配和结果聚合
 * - 支持 Worker 失败重试和降级
 */
public class SupervisorAgent extends BaseAgent {

    private final AgentRegistry agentRegistry;
    private final OrchestrationContext sharedContext;
    private final ExecutorService workerExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, WorkerAgent> activeWorkers = new ConcurrentHashMap<>();

    /**
     * 并行执行多个 Worker
     */
    private Map<String, String> executeParallel(
            Map<String, String> workerTasks,  // workerName → task
            Sinks.Many<String> sink,
            Duration timeout) {

        Map<String, CompletableFuture<String>> futures = new HashMap<>();

        for (var entry : workerTasks.entrySet()) {
            String workerName = entry.getKey();
            String task = entry.getValue();

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                WorkerAgent worker = createWorker(workerName, task);
                activeWorkers.put(workerName, worker);
                sink.tryEmitNext(createAgentStartEvent(workerName));

                StringBuilder output = new StringBuilder();
                worker.execute(UUID.randomUUID().toString(), task)
                        .doOnNext(chunk -> sink.tryEmitNext(chunk))
                        .doOnComplete(() -> sink.tryEmitNext(createAgentDoneEvent(workerName)))
                        .doOnError(err -> sink.tryEmitNext(createAgentErrorEvent(workerName, err)))
                        .blockLast();

                activeWorkers.remove(workerName);
                return output.toString();
            }, workerExecutor);

            futures.put(workerName, future);
        }

        // 等待所有完成 或 超时
        Map<String, String> results = new HashMap<>();
        for (var entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(),
                        entry.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                cancelWorker(entry.getKey());
                results.put(entry.getKey(), "[超时] " + entry.getKey() + " 未在规定时间内完成");
            } catch (Exception e) {
                results.put(entry.getKey(), "[错误] " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * 串行执行 Worker：上一个的输出作为下一个的输入
     */
    private String executeSequential(List<String> workerNames, String initialTask,
                                      Sinks.Many<String> sink) {
        String currentInput = initialTask;
        for (String workerName : workerNames) {
            sink.tryEmitNext(createAgentStartEvent(workerName));
            WorkerAgent worker = createWorker(workerName, currentInput);
            StringBuilder output = new StringBuilder();
            worker.execute(UUID.randomUUID().toString(), currentInput)
                    .blockLast();
            currentInput = worker.getFinalOutput();
            sink.tryEmitNext(createAgentDoneEvent(workerName));
        }
        return currentInput;
    }

    private WorkerAgent createWorker(String workerName, String task) {
        BaseAgent template = agentRegistry.getAgent(workerName);
        return WorkerAgent.builder()
                .workerId(workerName + "-" + UUID.randomUUID().toString().substring(0, 8))
                .chatModel(template.getChatModel())
                .roleDescription(template.getAgentMetadata().description())
                .assignedTools(agentRegistry.getToolsForAgent(workerName))
                .sharedContext(sharedContext)
                .build();
    }

    private void cancelWorker(String workerName) {
        WorkerAgent worker = activeWorkers.remove(workerName);
        if (worker != null) worker.cancel();
    }

    // SSE 事件方法
    private String createAgentStartEvent(String name) {
        return AgentResponse.json("agent_start", Map.of("agent", name, "status", "start"));
    }
    private String createAgentDoneEvent(String name) {
        return AgentResponse.json("agent_done", Map.of("agent", name, "status", "done"));
    }
    private String createAgentErrorEvent(String name, Throwable err) {
        return AgentResponse.json("agent_error", Map.of("agent", name, "error", err.getMessage()));
    }
}
```

### 5.5 AgentTaskManager 扩展（层级化）

```java
// AgentTaskManager 新增方法

// Redis Key 设计：
//   编排器任务:     agent:task:{conversationId}
//   子 Worker 任务:  agent:task:{conversationId}:{workerId}

public TaskInfo registerSubTask(String conversationId, String subTaskId,
                                  Sinks.Many<String> sink, String agentType) {
    // Redis key: agent:task:{conversationId}:{subTaskId}
    // TTL 继承父任务
}

public void cancelAllSubTasks(String conversationId) {
    // 级联取消：停止编排器时，同时停止所有 Worker
    taskMap.keySet().stream()
            .filter(key -> key.startsWith(conversationId + ":"))
            .forEach(this::doRemoveTask);
}
```

### 5.6 Phase 3 小结

| 项目 | 改动 |
|------|------|
| 新增文件 | `WorkerAgent`, `WorkerPool`, `SupervisorAgent`, `OrchestrationContext` |
| 修改文件 | `AgentTaskManager` 增加层级化方法, `AgentRegistry` 增加工具分配 |
| 风险 | 每个 Worker 一个 ChatClient，内存占用增加；Worker 间通信复杂度高 |
| 前置条件 | Phase 1+2 已验证稳定，业务确实需要完全自主的多 Agent |

---

## 6. 数据模型变更

### 6.1 ChatMessage 表扩展

```sql
-- 新增字段
ALTER TABLE chat_message ADD COLUMN agent_trace_id VARCHAR(64) COMMENT 'Agent 调用链追踪 ID';
ALTER TABLE chat_message ADD COLUMN parent_message_id BIGINT COMMENT '父消息 ID（Sub-Agent 调用来源）';
ALTER TABLE chat_message ADD COLUMN agent_name VARCHAR(64) COMMENT '产生此消息的 Agent 名称';
ALTER TABLE chat_message ADD COLUMN agent_role VARCHAR(32) COMMENT 'Agent 角色：orchestrator/worker/search/planner';

-- 索引
CREATE INDEX idx_agent_trace_id ON chat_message(agent_trace_id);
CREATE INDEX idx_parent_message_id ON chat_message(parent_message_id);
```

### 6.2 调用链追踪

```
用户问题 (message_id=100, agent_name="orchestrator", agent_trace_id="trace-001")
  ├── search_agent 调用 (message_id=101, parent_message_id=100, agent_name="search_agent")
  │   ├── tavily_search 工具返回 (message_id=102, parent_message_id=101)
  │   └── search_agent 结果 (message_id=103, parent_message_id=101)
  └── orchestrator 最终答案 (message_id=104, parent_message_id=100)
```

### 6.3 AiSession 表（不变）

当前 `ai_session` 存储单次对话的 question/answer/thinking/tools/reference 仍适用 — 多 Agent 场景下 answer 字段存放 Orchestrator 的最终输出，Sub-Agent 的中间输出通过 `chat_message` 表追踪。

---

## 7. API & SSE 协议扩展

### 7.1 新增 SSE 事件类型

```java
// AgentResponse.java 新增常量
public static final String TYPE_AGENT_START = "agent_start";   // Sub-Agent 开始执行
public static final String TYPE_AGENT_DONE  = "agent_done";    // Sub-Agent 执行完成
public static final String TYPE_AGENT_ERROR = "agent_error";   // Sub-Agent 执行失败
public static final String TYPE_PLAN        = "plan";          // PlanExecute 计划输出
public static final String TYPE_CRITIQUE    = "critique";      // PlanExecute 评审输出
```

### 7.2 SSE 事件示例

多 Agent 场景下的一条完整 SSE 流：

```
data: {"type":"text","content":"我来为你深入研究这个问题。"}
data: {"type":"plan","content":"[{\"id\":\"t1\",\"instruction\":\"搜索A相关\",\"order\":1},{\"id\":\"t2\",\"instruction\":\"搜索B相关\",\"order\":1}]"}
data: {"type":"agent_start","content":"{\"agent\":\"search_agent\",\"status\":\"start\",\"task\":\"搜索A相关\"}"}
data: {"type":"text","content":"搜索到以下信息..."}           ← search_agent 输出
data: {"type":"reference","content":"[...]","count":3}        ← search_agent 引用
data: {"type":"agent_done","content":"{\"agent\":\"search_agent\",\"status\":\"done\"}"}
data: {"type":"agent_start","content":"{\"agent\":\"search_agent\",\"status\":\"start\",\"task\":\"搜索B相关\"}"}
data: {"type":"text","content":"关于B的信息..."}
data: {"type":"agent_done","content":"{\"agent\":\"search_agent\",\"status\":\"done\"}"}
data: {"type":"text","content":"综合以上信息，结论如下：..."}   ← orchestrator 最终输出
data: {"type":"recommend","content":"[...]","count":3}
data: {"type":"done","content":"conversationId"}
```

### 7.3 新增 API 端点

全部多 Agent 接口集中在 `MultiAgentController`（路径前缀 `/multi-agent`），`AgentController`（路径前缀 `/agent`）保持不变。

```java
// MultiAgentController.java

/**
 * 多 Agent 协同流式接口（Phase 1 起可用）
 *
 * mode 参数：
 *   auto          — LLM 自主决策是否调度 Sub-Agent（默认，Phase 1）
 *   plan_execute  — 强制走 Plan-Execute-Critique 深度研究流程（Phase 2）
 *   supervisor    — Supervisor-Worker 完全自主模式（Phase 3）
 */
@GetMapping("/multi-agent/collaborate/stream")
public Flux<String> collaborateStream(
        @RequestParam String query,
        @RequestParam String conversationId,
        @RequestParam(defaultValue = "auto") String mode,
        @RequestParam(required = false) Boolean think,
        @RequestParam(required = false) String fileIds);

/**
 * 停止多 Agent 协同执行
 */
@GetMapping("/multi-agent/stop")
public Map<String, Object> stopCollaborate(@RequestParam String conversationId);

/**
 * 获取当前会话的 Agent 调用链追踪（Phase 2+）
 * 前端用于展示多 Agent "思考过程"面板
 */
@GetMapping("/multi-agent/trace/{conversationId}")
public Map<String, Object> getAgentTrace(@PathVariable String conversationId);

/**
 * 编程式编排：链式执行（Phase 2+）
 * POST body 指定 Agent 执行顺序
 */
@PostMapping("/multi-agent/chain/stream")
public Flux<String> chainStream(@RequestBody ChainRequest request);
```

### 7.4 API 端点全景

```
单 Agent 对话（保留不变）:
  GET  /agent/chat/stream          ← AgentController → DearAgent
  GET  /agent/stop                 ← AgentController

多 Agent 协同（新增）:
  GET  /multi-agent/collaborate/stream  ← MultiAgentController → SimpleOrchestrator
  GET  /multi-agent/stop                ← MultiAgentController
  GET  /multi-agent/trace/{id}          ← MultiAgentController (Phase 2+)
  POST /multi-agent/chain/stream        ← MultiAgentController (Phase 2+)

语音（保留）:
  GET  /api/agent/stream-with-voice
  GET  /api/agent/quick
```

---

## 8. 风险与对策

### 8.1 Token 成本

| 风险 | 对策 |
|------|------|
| Plan-Execute-Critique 循环每轮多次 LLM 调用 | Plan/Critique/Compress 使用轻量模型（如 qwen-turbo），仅 Execute 和 Summarize 使用强模型 |
| 上下文随循环轮次膨胀 | Compress 阶段主动压缩；设置 maxResearchLoops=5 |
| Sub-Agent 并发时总 token 翻倍 | AgentRegistry 中为每个 Agent 指定建议模型级别 |

### 8.2 延迟

| 风险 | 对策 |
|------|------|
| 深度研究可能耗时 30-60s | SSE 实时推送每个阶段的进度（plan/agent_start/agent_done） |
| 串行 Agent 链总延迟 = 各环节之和 | 可并行的阶段（Plan 中 order 相同的任务）并行执行 |
| Worker 无响应 | 设置 timeout（30s），超时后降级或重试 |

### 8.3 架构复杂度

| 风险 | 对策 |
|------|------|
| 三阶段演进可能过度设计 | Phase 1 即可满足 80% 场景，Phase 2/3 严格按需推进 |
| Agent 间状态一致性 | OrchestrationContext 使用 ConcurrentHashMap；避免分布式事务，接受最终一致性 |
| 前端适配成本 | 新增 SSE type 可渐进适配 — 前端忽略未知 type 即可降级展示 |

### 8.4 与现有系统的兼容

| 保证措施 |
|----------|
| `AgentController` **零改动** — 原 `/agent/chat/stream` 路径、`DearAgent` 构建逻辑、SSE 输出格式全部保留不变 |
| 多 Agent 功能全部收敛在 `MultiAgentController` 和新增类中，与现有代码物理隔离 |
| `SimpleOrchestrator` 继承 `DearAgent`，不修改 DearAgent 源码 |
| 新增 SSE type 为可选扩展，旧前端忽略未知 type 不报错 |
| AgentRegistry 为空时（未注册任何 Sub-Agent），编排器退化为普通 DearAgent 行为 |
| 两个 Controller 可独立部署 — 即使 `MultiAgentController` 出问题，`/agent/chat/stream` 不受影响 |

---

## 附录 A：实施检查清单

### Phase 1

- [ ] `AgentMetadata` 接口定义
- [ ] `AgentRegistry` 注册中心
- [ ] `AgentToolAdapter` 适配器
- [ ] `SimpleOrchestrator` 编排器
- [ ] `WebSearchReactAgent` 实现 `AgentMetadata`
- [ ] `AgentAutoConfiguration` 自动注册
- [ ] `MultiAgentController` 实现 + 路由逻辑
- [ ] `AgentController` 零改动验证（回归测试通过）
- [ ] 单元测试：Agent 注册/查找/适配
- [ ] 集成测试：编排器 + 搜索 Agent 协作
- [ ] 前端验证：`/agent/chat/stream` 和 `/multi-agent/collaborate/stream` 两条路径均正常

### Phase 2

- [ ] `PlanExecuteOrchestrator` 核心编排逻辑
- [ ] Plan → Execute → Critique → Compress → Summarize 各阶段实现
- [ ] Light model 集成（ModelRegistry 扩展）
- [ ] `AgentOrchestrator` 编程式 API（chain/parallel/route）
- [ ] `ResearchPipeline` 预置流水线
- [ ] 集成测试：模拟深度研究报告生成
- [ ] Token 消耗监控埋点

### Phase 3

- [ ] `WorkerAgent` 独立 Worker 实现
- [ ] `OrchestrationContext` 共享黑板
- [ ] `SupervisorAgent` 管理者 Agent
- [ ] `WorkerPool` Worker 池管理
- [ ] `AgentTaskManager` 层级化扩展
- [ ] 数据库 `chat_message` 表字段变更
- [ ] 新增 SSE 事件类型
- [ ] 新增 API 端点
- [ ] 压力测试：10 并发 Worker

---

## 附录 B：关键类图

```
                    ┌─────────────────┐
                    │    BaseAgent     │ (抽象基类)
                    │  - chatModel     │
                    │  - chatMemory    │
                    │  - taskManager   │
                    │  + execute()     │
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
  ┌───────▼───────┐  ┌──────▼──────┐  ┌────────▼────────┐
  │   DearAgent   │  │  ReactAgent │  │ OrchestratorAgent│ (Phase 1-2)
  │  (生产级 Agent) │  │ (独立 Agent) │  │  - agentRegistry │
  └───────┬───────┘  └─────────────┘  │  - executePlan() │
          │                            │  - critique()    │
          │                            └────────┬────────┘
          │                                     │
  ┌───────▼───────┐                    ┌────────▼────────┐
  │SimpleOrchestra│                    │PlanExecute      │
  │    tor        │ (Phase 1)          │Orchestrator     │ (Phase 2)
  │+ Agent 工具合并 │                    │+ 五阶段流程      │
  └───────────────┘                    └─────────────────┘

          ┌─────────────────────────────────────┐
          │           AgentRegistry              │
          │  - agents: Map<String, BaseAgent>    │
          │  + register(agent, metadata)         │
          │  + getAgent(name): BaseAgent         │
          │  + getAgentTools(): List<ToolCallback>│
          └─────────────────────────────────────┘
                         │
                         │ 实现
                         ▼
          ┌─────────────────────────────────────┐
          │         AgentMetadata (接口)          │
          │  + agentName(): String               │
          │  + description(): String             │
          │  + role(): String                    │
          │  + callSync(input): String           │
          └─────────────────────────────────────┘

          ┌─────────────────────────────────────┐
          │         AgentToolAdapter             │
          │  implements ToolCallback             │
          │  - agent: BaseAgent                  │
          │  - metadata: AgentMetadata           │
          │  + getToolDefinition()               │
          │  + call(toolInput): ToolCallResult   │
          └─────────────────────────────────────┘
```
