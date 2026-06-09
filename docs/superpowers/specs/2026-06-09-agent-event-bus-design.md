# Agent Event Bus 全面重构设计

> 将 `AgentResponse` 原始字符串事件模型替换为基于事件总线的分阶段、结构化事件体系。

---

## 1. 动机

### 1.1 现状问题

| 维度 | 现状 | 痛点 |
|------|------|------|
| 事件层级 | 18 种 type 常量平铺在一个类 | 无生命周期，前端无法感知阶段 |
| 数据结构 | `content` 是万能字符串，有时内嵌 JSON | 前端需要二次 `JSON.parse`，解析路径不统一 |
| 工具状态 | `{type:"function", content:"{\"tool\":\"x\",\"status\":\"start\"}"}` | JSON 套 JSON，字段名不固定 |
| 耦合 | Agent 直接调 `AgentResponse.text()` 生成 SSE 字符串 | 无法复用，日志/持久化/指标各自解析 |
| 扩展 | 每加一种事件 = 加一个 type 常量 + 一个 static 方法 | 类膨胀，新同事不知道用哪个 |

### 1.2 目标

- **分阶段输出**：每个逻辑阶段（THINKING / RESPONDING / EXECUTING / COLLABORATING / PLANNING）有明确的进入/退出事件
- **统一结构化事件**：每种事件有固定字段，前端按 `type` 走 `switch` 即可，无需猜测 `content` 格式
- **事件总线解耦**：Agent 只 `publish(Event)`，SSE 推送、DB 持久化、指标采集各自订阅
- **前端友好**：每个事件 type 对应一个 TypeScript interface，协议自描述

---

## 2. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                       Agent 层                                │
│  DearAgent / WebSearchReactAgent / PlanExecuteAgent / ...    │
│         │                                                    │
│         │ eventBus.publish(new ThinkingStartEvent())          │
│         │ eventBus.publish(new TextDeltaEvent("hello"))       │
│         ▼                                                    │
│  ┌─────────────────────────────────────────────────────┐     │
│  │                  AgentEventBus                       │     │
│  │                                                     │     │
│  │  Sinks.Many<AgentEvent> (Reactor 多播)              │     │
│  │  + PhaseTracker (当前阶段追踪)                       │     │
│  │  + EventFilter (按 type/phase 路由)                  │     │
│  └──────┬──────────────┬──────────────┬────────────────┘     │
│         │              │              │                       │
│         ▼              ▼              ▼                       │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────┐      │
│  │   SSE    │  │   Session    │  │   MetricsLogger   │      │
│  │ Emitter  │  │  Persister   │  │   (可观测)         │      │
│  │          │  │              │  │                    │      │
│  │ Flux →   │  │ 组装文本 →   │  │ 计数/耗时 →        │      │
│  │ HTTP SSE │  │ DB save      │  │ micrometer         │      │
│  └──────────┘  └──────────────┘  └───────────────────┘      │
└──────────────────────────────────────────────────────────────┘
```

**关键原则**：
- Agent **只写事件，不读订阅者** — 不关心事件被谁消费
- 总线是 **单例 Spring Bean**，通过构造器注入到 Agent
- SSE Emitter 和 Session Persister 在总线启动时注册订阅

---

## 3. 事件体系

### 3.1 基类

```java
package com.roydon.dear.event;

public abstract class AgentEvent {
    private final String eventId;      // UUID
    private final long timestamp;      // System.currentTimeMillis()
    private final AgentPhase phase;    // 所属阶段
    private final String type;         // SSE type 字段值

    // 子类通过 super(type, phase) 调用
    protected AgentEvent(String type, AgentPhase phase) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.phase = phase;
    }

    // 子类实现：序列化为 SSE JSON 字符串
    public abstract String toSseJson();
}
```

### 3.2 阶段枚举

```java
public enum AgentPhase {
    THINKING,       // 模型思考（thinking_start → thinking_text → thinking_end）
    RESPONDING,     // 文本输出（text_delta）
    EXECUTING,      // 工具调用（tool_start → tool_end / tool_error）
    KNOWLEDGE,      // 知识库检索
    COLLABORATING,  // 多 Agent 协同
    PLANNING,       // Plan-Execute 计划
    COMPLETED,      // 流结束
    ERROR           // 全局错误
}
```

### 3.3 Thinking 阶段事件

```java
// thinking_start — 模型开始思考
public class ThinkingStartEvent extends AgentEvent {
    public ThinkingStartEvent() { super("thinking_start", AgentPhase.THINKING); }
    // no extra fields
}

// thinking_text — 思考过程增量
public class ThinkingTextEvent extends AgentEvent {
    private final String text;
    public ThinkingTextEvent(String text) {
        super("thinking_text", AgentPhase.THINKING);
        this.text = text;
    }
}

// thinking_end — 思考结束
public class ThinkingEndEvent extends AgentEvent {
    private final long durationMs;   // 思考耗时（毫秒）
    public ThinkingEndEvent(long durationMs) {
        super("thinking_end", AgentPhase.THINKING);
        this.durationMs = durationMs;
    }
}
```

### 3.4 RESPONDING 阶段事件

```java
// text_delta — 最终回复增量
public class TextDeltaEvent extends AgentEvent {
    private final String text;
    public TextDeltaEvent(String text) {
        super("text_delta", AgentPhase.RESPONDING);
        this.text = text;
    }
}
```

### 3.5 EXECUTING 阶段事件

```java
// tool_start — 工具调用开始
public class ToolStartEvent extends AgentEvent {
    private final String id;         // tool call id (from LLM)
    private final String name;       // tool name
    private final Map<String, Object> input;  // 工具参数（已解析的 JSON 对象）

    public ToolStartEvent(String id, String name, Map<String, Object> input) {
        super("tool_start", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.input = input;
    }
}

// tool_end — 工具调用成功
public class ToolEndEvent extends AgentEvent {
    private final String id;
    private final String name;
    private final boolean success;
    private final String result;     // 工具结果（截断至 500 字以内）

    public ToolEndEvent(String id, String name, String result) {
        super("tool_end", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.success = true;
        this.result = result;
    }
}

// tool_error — 工具调用失败
public class ToolErrorEvent extends AgentEvent {
    private final String id;
    private final String name;
    private final String error;      // 错误信息

    public ToolErrorEvent(String id, String name, String error) {
        super("tool_error", AgentPhase.EXECUTING);
        this.id = id;
        this.name = name;
        this.error = error;
    }
}
```

### 3.6 KNOWLEDGE 阶段事件

```java
// knowledge_start — 开始知识库检索
public class KnowledgeStartEvent extends AgentEvent {
    public KnowledgeStartEvent() { super("knowledge_start", AgentPhase.KNOWLEDGE); }
}

// knowledge_end — 检索完成（仅推送元数据，不含完整文档内容）
public class KnowledgeEndEvent extends AgentEvent {
    private final List<KnowledgeItem> items;  // {score, metadata}
    private final int count;

    public KnowledgeEndEvent(List<KnowledgeItem> items, int count) {
        super("knowledge_end", AgentPhase.KNOWLEDGE);
        this.items = items;
        this.count = count;
    }

    public record KnowledgeItem(double score, Map<String, Object> metadata) {}
}
```

### 3.7 COLLABORATING 阶段事件（多 Agent）

```java
// agent_start — 子 Agent 开始执行
public class AgentStartEvent extends AgentEvent {
    private final String agentId;    // "web_search_agent"
    private final String task;       // 分配给此 Agent 的任务

    public AgentStartEvent(String agentId, String task) {
        super("agent_start", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.task = task;
    }
}

// agent_text — 子 Agent 产出的文本（透明转发）
public class AgentTextEvent extends AgentEvent {
    private final String agentId;
    private final String text;

    public AgentTextEvent(String agentId, String text) {
        super("agent_text", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.text = text;
    }
}

// agent_tool_start — 子 Agent 的工具调用
public class AgentToolStartEvent extends AgentEvent {
    private final String agentId;
    private final String id;
    private final String name;
    private final Map<String, Object> input;

    public AgentToolStartEvent(String agentId, String id, String name, Map<String, Object> input) {
        super("agent_tool_start", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.id = id;
        this.name = name;
        this.input = input;
    }
}

// agent_tool_end — 子 Agent 的工具调用完成
public class AgentToolEndEvent extends AgentEvent {
    private final String agentId;
    private final String id;
    private final boolean success;
    private final String result;

    public AgentToolEndEvent(String agentId, String id, String result) {
        super("agent_tool_end", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.id = id;
        this.success = true;
        this.result = result;
    }
}

// agent_done — 子 Agent 执行完成
public class AgentDoneEvent extends AgentEvent {
    private final String agentId;
    private final String result;     // 子 Agent 的完整输出（用于持久化，前端可折叠）

    public AgentDoneEvent(String agentId, String result) {
        super("agent_done", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.result = result;
    }
}

// agent_error — 子 Agent 执行失败
public class AgentErrorEvent extends AgentEvent {
    private final String agentId;
    private final String error;

    public AgentErrorEvent(String agentId, String error) {
        super("agent_error", AgentPhase.COLLABORATING);
        this.agentId = agentId;
        this.error = error;
    }
}
```

### 3.8 PLANNING 阶段事件（PlanExecuteAgent）

```java
// plan_created — 已生成执行计划
public class PlanCreatedEvent extends AgentEvent {
    private final List<PlanStep> steps;

    public PlanCreatedEvent(List<PlanStep> steps) {
        super("plan_created", AgentPhase.PLANNING);
        this.steps = steps;
    }
}

// plan_step_start
public class PlanStepStartEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String instruction;
    private final int order;

    public PlanStepStartEvent(String stepId, String title, String instruction, int order) {
        super("plan_step_start", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.instruction = instruction;
        this.order = order;
    }
}

// plan_step_end
public class PlanStepEndEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String result;     // 截断至 500 字

    public PlanStepEndEvent(String stepId, String title, String result) {
        super("plan_step_end", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.result = result;
    }
}

// plan_step_error
public class PlanStepErrorEvent extends AgentEvent {
    private final String stepId;
    private final String title;
    private final String error;

    public PlanStepErrorEvent(String stepId, String title, String error) {
        super("plan_step_error", AgentPhase.PLANNING);
        this.stepId = stepId;
        this.title = title;
        this.error = error;
    }
}
```

### 3.9 COMPLETED 与 ERROR 阶段

```java
// done — 流正常结束
public class DoneEvent extends AgentEvent {
    private final String conversationId;
    private final long totalDurationMs;
    private final int roundCount;
    private final List<String> usedTools;       // 使用的工具名称列表
    private final int referenceCount;           // 引用数量
    private final String recommendations;       // JSON 数组（推荐问题），可为 null

    public DoneEvent(String conversationId, long totalDurationMs, int roundCount,
                     List<String> usedTools, int referenceCount, String recommendations) {
        super("done", AgentPhase.COMPLETED);
        this.conversationId = conversationId;
        this.totalDurationMs = totalDurationMs;
        this.roundCount = roundCount;
        this.usedTools = usedTools;
        this.referenceCount = referenceCount;
        this.recommendations = recommendations;
    }
}

// error — 全局错误
public class ErrorEvent extends AgentEvent {
    private final String conversationId;
    private final String message;
    private final String code;       // 错误码，如 "MODEL_ERROR"、"TOOL_ERROR"

    public ErrorEvent(String conversationId, String message, String code) {
        super("error", AgentPhase.ERROR);
        this.conversationId = conversationId;
        this.message = message;
        this.code = code;
    }
}
```

### 3.10 reference 与 recommend（有损迁移，保留）

这两个事件是**终端事件**（在 done 之前只发一次，不是增量数据），保持原有逻辑，但改用新事件包装：

```java
// reference — 搜索结果引用列表（done 之前一次性发送）
public class ReferenceEvent extends AgentEvent {
    private final List<SearchResultItem> items;

    public ReferenceEvent(List<SearchResultItem> items) {
        super("reference", AgentPhase.RESPONDING);
        this.items = items;
    }

    public record SearchResultItem(String url, String title, String content) {}
}

// recommend — 推荐问题（done 之前一次性发送）
public class RecommendEvent extends AgentEvent {
    private final List<String> questions;

    public RecommendEvent(List<String> questions) {
        super("recommend", AgentPhase.RESPONDING);
        this.questions = questions;
    }
}
```

---

## 4. 事件总线

### 4.1 接口

```java
package com.roydon.dear.event;

public interface AgentEventBus {
    /** 发布事件 */
    void publish(AgentEvent event);

    /** 转换为 Reactor Flux（供 SSE Emitter 消费） */
    Flux<AgentEvent> asFlux();

    /** 按事件类型订阅 */
    <T extends AgentEvent> Disposable on(Class<T> eventType, Consumer<T> listener);

    /** 按阶段订阅 */
    Disposable on(AgentPhase phase, Consumer<AgentEvent> listener);

    /** 订阅全部（供日志/指标） */
    Disposable onAll(Consumer<AgentEvent> listener);

    /** 查询当前阶段 */
    AgentPhase currentPhase();

    /** 结束事件流（done 或 error 时调用） */
    void complete();
}
```

### 4.2 实现

```java
@Component
public class DefaultAgentEventBus implements AgentEventBus {
    private final Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
    private final Flux<AgentEvent> flux = sink.asFlux().publish().autoConnect();
    private final AtomicReference<AgentPhase> phaseRef = new AtomicReference<>();

    @Override
    public void publish(AgentEvent event) {
        phaseRef.set(event.getPhase());
        sink.tryEmitNext(event);
    }

    @Override
    public Flux<AgentEvent> asFlux() { return flux; }

    @Override
    public <T extends AgentEvent> Disposable on(Class<T> type, Consumer<T> listener) {
        return flux.ofType(type).subscribe(listener::accept);
    }

    @Override
    public Disposable on(AgentPhase phase, Consumer<AgentEvent> listener) {
        return flux.filter(e -> e.getPhase() == phase).subscribe(listener::accept);
    }

    @Override
    public Disposable onAll(Consumer<AgentEvent> listener) {
        return flux.subscribe(listener::accept);
    }

    @Override
    public AgentPhase currentPhase() { return phaseRef.get(); }

    @Override
    public void complete() { sink.tryEmitComplete(); }
}
```

### 4.3 Spring 装配

```java
@Configuration
public class EventBusConfiguration {

    @Bean
    public AgentEventBus agentEventBus() {
        return new DefaultAgentEventBus();
    }

    /** SSE Emitter: 将事件总线转为 HTTP SSE 流 */
    @Bean
    public SseEventEmitter sseEventEmitter(AgentEventBus eventBus) {
        return new SseEventEmitter(eventBus);
    }

    /** Session Persister: 收集 text/thinking/tool 事件，组装最终答案写 DB */
    @Bean
    public SessionEventPersister sessionEventPersister(AgentEventBus eventBus,
                                                        ChatConversationService convSvc,
                                                        ChatMessageService msgSvc) {
        return new SessionEventPersister(eventBus, convSvc, msgSvc);
    }
}
```

---

## 5. SSE 序列化

### 5.1 SseEventEmitter

```java
public class SseEventEmitter {
    private final AgentEventBus eventBus;

    /** 返回 Flux<String>，可直接作为 Controller 的返回值 */
    public Flux<String> toSseFlux(AgentEventBus bus) {
        return bus.asFlux()
            .map(event -> "data: " + event.toSseJson() + "\n\n")
            .doFinally(sig -> bus.complete());
    }
}
```

### 5.2 序列化方式

每个事件类的 `toSseJson()` 用 Jackson 序列化。**不再有 JSON 套 JSON**。示例对比：

```
// 旧格式（think 阶段无，工具状态 JSON 套 JSON）
{"type":"function","content":"{\"tool\":\"tavily_search\",\"status\":\"start\",\"args\":\"{\\\"query\\\":\\\"AI趋势\\\"}\"}"}
{"type":"function","content":"{\"tool\":\"tavily_search\",\"status\":\"done\",\"result\":\"...\"}"}

// 新格式（每个 type 有独立结构，字段类型化）
{"type":"thinking_start","phase":"THINKING","eventId":"uuid-1","timestamp":1718000000000}
{"type":"thinking_text","phase":"THINKING","eventId":"uuid-2","timestamp":1718000000100,"text":"让我分析..."}
{"type":"thinking_end","phase":"THINKING","eventId":"uuid-3","timestamp":1718000003000,"durationMs":2900}
{"type":"tool_start","phase":"EXECUTING","eventId":"uuid-4","timestamp":...,"id":"call_01","name":"tavily_search","input":{"query":"AI趋势"}}
{"type":"tool_end","phase":"EXECUTING","eventId":"uuid-5","timestamp":...,"id":"call_01","name":"tavily_search","success":true,"result":"搜索结果摘要..."}
{"type":"text_delta","phase":"RESPONDING","eventId":"uuid-6","timestamp":...,"text":"根据"}
{"type":"text_delta","phase":"RESPONDING","eventId":"uuid-7","timestamp":...,"text":"搜索结果"}
{"type":"reference","phase":"RESPONDING","eventId":"uuid-8","timestamp":...,"items":[{"url":"...","title":"...","content":"..."}]}
{"type":"recommend","phase":"RESPONDING","eventId":"uuid-9","timestamp":...,"questions":["AI对就业的影响","..."]}
{"type":"done","phase":"COMPLETED","eventId":"uuid-10","timestamp":...,"conversationId":"xxx","totalDurationMs":5200,"roundCount":2,"usedTools":["tavily_search"],"referenceCount":5,"recommendations":"[...]"}
```

---

## 6. Agent 改造

### 6.1 BaseAgent 变更

```java
// 注入 eventBus，替代 createXxxResponse 方法
public abstract class BaseAgent {
    protected AgentEventBus eventBus;  // 新增

    // 移除所有 createXxxResponse() 方法
    // 移除 AgentResponse 依赖
}
```

### 6.2 DearAgent 改造示例（核心流）

改造前：

```java
// 旧：直接构造 SSE 字符串
sink.tryEmitNext(createThinkingResponse(thinkingText));
sink.tryEmitNext(createTextResponse(text));
sink.tryEmitNext(emitToolStatus(toolType, jsonString));
```

改造后：

```java
// 新：发布结构化事件
eventBus.publish(new ThinkingStartEvent());
// thinking 流式到达时
eventBus.publish(new ThinkingTextEvent(thinkingText));
// thinking 结束时
eventBus.publish(new ThinkingEndEvent(durationMs));

// 工具调用
eventBus.publish(new ToolStartEvent(tc.id(), toolName, parseArgs(argsJson)));
// 工具执行完后
eventBus.publish(new ToolEndEvent(tc.id(), toolName, truncatedResult));

// 文本增量
eventBus.publish(new TextDeltaEvent(text));

// 结束时
eventBus.publish(new DoneEvent(conversationId, totalMs, rounds, tools, refCount, recommendations));
```

### 6.3 调用点统计

| 文件 | 改造点数 | 说明 |
|------|---------|------|
| `BaseAgent` | 移除 15 个 createXxx 方法 | 加 1 个 eventBus 字段 |
| `DearAgent` | ~15 处 sink.tryEmitNext | 替换为 eventBus.publish |
| `WebSearchReactAgent` | ~8 处 sink.tryEmitNext | 替换为 eventBus.publish |
| `PlanExecuteAgent` | ~10 处 sink.tryEmitNext | 替换为 eventBus.publish |
| `SimpleOrchestrator` | ~3 处 emitToolStatus | 替换为 AgentStart/AgentDone 事件 |
| `AgentController` | 返回值类型不变 | Flux<String> 由 SseEventEmitter 生成 |
| `MultiAgentController` | 同上 | 同上 |

### 6.4 Controller 变更

```java
// AgentController: 返回值不再由 Agent 直接提供 Flux<String>
// 改为：启动 Agent → Agent 向 eventBus 发布事件 → Controller 返回 SSE Flux

@GetMapping("/chat/stream")
public Flux<String> chatStream(@RequestParam String query, ...) {
    // 每个请求创建独立的 AgentEventBus 实例（prototype scope）
    AgentEventBus bus = eventBusFactory.create();

    // 启动 Agent（异步，publish 事件到 bus）
    dearAgent.execute(bus, conversationId, query, ...);

    // 返回 SSE 流
    return sseEventEmitter.toSseFlux(bus);
}
```

**关键设计**：`AgentEventBus` 是 prototype scope — 每个请求一个独立总线实例，事件流在该请求内隔离。全局日志/指标监听器可以订阅一个全局共享的总线。

---

## 7. 流式渲染与历史渲染一致性

### 7.1 核心问题

实时 SSE 流和 DB 历史加载是两条渲染路径，如果数据结构不同，前端必须维护两套渲染逻辑，必然出现 UI 不一致：

```
实时流式:  Agent → EventBus → SSE → 前端逐事件渲染
历史加载:  DB → API 响应 → 前端需要拼装 → ⚠️ 与实时渲染不同
```

### 7.2 方案：event_stream 作为单一数据源

**把完整事件流存入 DB。历史加载时回放同一个事件数组，经过同一条渲染函数。**

```
实时流式:  Agent → EventBus.publish(Event) → SSE → handleEvent(event)
                                                      ↑
历史加载:  DB → event_stream JSON 数组 → forEach ────┘
```

### 7.3 数据模型

```sql
-- ai_chat_message 表变更
ALTER TABLE ai_chat_message ADD COLUMN event_stream MEDIUMTEXT COMMENT '完整事件流 JSON 数组';
-- 废弃字段（保留向后兼容，新数据不再写入）：
--   thinking, tools, reference, recommend, knowledge, firstResponseTime, totalResponseTime
--   这些字段的数据全部包含在 event_stream 中，可由事件派生
```

`event_stream` 是一份**完整的事件 JSON 数组**，示例：

```json
[
  {"type":"thinking_start","phase":"THINKING","eventId":"e1","timestamp":1700000000000},
  {"type":"thinking_text","phase":"THINKING","text":"让我分析这个问题..."},
  {"type":"thinking_end","phase":"THINKING","durationMs":1200},
  {"type":"tool_start","phase":"EXECUTING","id":"c1","name":"tavily_search","input":{"query":"AI趋势"}},
  {"type":"tool_end","phase":"EXECUTING","id":"c1","name":"tavily_search","success":true,"result":"..."},
  {"type":"text_delta","phase":"RESPONDING","text":"根据搜索结果"},
  {"type":"reference","phase":"RESPONDING","items":[{"url":"...","title":"...","content":"..."}]},
  {"type":"recommend","phase":"RESPONDING","questions":["AI对就业的影响","..."]},
  {"type":"done","phase":"COMPLETED","conversationId":"xxx","totalDurationMs":3200,"roundCount":2,...}
]
```

### 7.4 SessionEventPersister 实现

```java
public class SessionEventPersister {
    private final List<AgentEvent> allEvents = new ArrayList<>();

    public void attach(AgentEventBus bus) {
        // 收集全部事件
        bus.onAll(event -> allEvents.add(event));

        bus.on(DoneEvent.class, e -> {
            String eventStreamJson = objectMapper.writeValueAsString(allEvents);
            // event_stream 是唯一写入字段，废弃 content/thinking/tools/reference 等
            messageService.saveAssistantMessage(conversationId, replyId, eventStreamJson);
        });

        bus.on(ErrorEvent.class, e -> {
            messageService.saveAssistantMessage(conversationId, replyId,
                objectMapper.writeValueAsString(allEvents));
        });
    }
}
```

### 7.5 ChatMessage 表变更

```java
// ChatMessage 实体：新增 eventStream，废弃旧字段
@Data
@TableName("ai_chat_message")
public class ChatMessage {
    // ... id, conversationId, replyId, messageType 不变
    private String eventStream;      // ★ 唯一数据源：完整事件流 JSON 数组
    // 以下字段保留但不写入新数据，仅用于旧数据兼容
    @Deprecated private String content;
    @Deprecated private String thinking;
    @Deprecated private String tools;
    @Deprecated private String reference;
    @Deprecated private String recommend;
    @Deprecated private String knowledge;
    @Deprecated private Long firstResponseTime;
    @Deprecated private Long totalResponseTime;
}
```

### 7.6 历史消息 API

```json
// GET /session/{id}/messages
{
  "messages": [
    {
      "id": 1,
      "messageType": "user",
      "content": "帮我研究AI趋势",
      "createTime": "..."
    },
    {
      "id": 2,
      "messageType": "assistant",
      "eventStream": [
        {"type":"thinking_start","phase":"THINKING",...},
        {"type":"thinking_text","text":"让我分析..."},
        {"type":"thinking_end","durationMs":1200},
        {"type":"tool_start","id":"c1","name":"tavily_search","input":{...}},
        {"type":"tool_end","id":"c1","name":"tavily_search","success":true,"result":"..."},
        {"type":"text_delta","text":"根据"},
        {"type":"text_delta","text":"搜索结果"},
        {"type":"reference","items":[...]},
        {"type":"recommend","questions":[...]},
        {"type":"done","conversationId":"xxx","totalDurationMs":3200,...}
      ],
      "createTime": "..."
    }
  ]
}
```

### 7.7 前端统一渲染

```typescript
// 实时流式：EventSource 逐事件推入
// 历史加载：eventStream 数组遍历推入
// 两条路径走同一个处理器

function useAgentEvents(): {
  events: AnyAgentEvent[];
  pushEvent: (e: AnyAgentEvent) => void;
} {
  const [events, setEvents] = useState<AnyAgentEvent[]>([]);
  const pushEvent = useCallback((e: AnyAgentEvent) => {
    setEvents(prev => [...prev, e]);
  }, []);
  return { events, pushEvent };
}

// 实时 SSE
function StreamChat() {
  const { events, pushEvent } = useAgentEvents();
  useEffect(() => {
    const es = new EventSource(url);
    es.onmessage = (msg) => pushEvent(JSON.parse(msg.data));
    return () => es.close();
  }, []);
  return <AgentMessage events={events} />;       // ← 统一渲染组件
}

// 历史消息
function HistoryChat({ message }: { message: MessageVO }) {
  const { events } = useAgentEvents();
  useEffect(() => {
    message.eventStream.forEach(e => pushEvent(e));  // 回放
  }, [message.id]);
  return <AgentMessage events={events} />;       // ← 同一个组件
}
```

---

## 8. 模块结构

```
module-event/                              # 新建模块
├── pom.xml
└── src/main/java/com/roydon/dear/event/
    ├── AgentEvent.java                    # 抽象基类
    ├── AgentPhase.java                    # 阶段枚举
    ├── AgentEventBus.java                 # 总线接口
    ├── DefaultAgentEventBus.java          # 总线实现
    ├── SseEventEmitter.java               # SSE 序列化
    ├── SessionEventPersister.java         # DB 持久化监听器
    ├── events/
    │   ├── ThinkingStartEvent.java
    │   ├── ThinkingTextEvent.java
    │   ├── ThinkingEndEvent.java
    │   ├── TextDeltaEvent.java
    │   ├── ToolStartEvent.java
    │   ├── ToolEndEvent.java
    │   ├── ToolErrorEvent.java
    │   ├── KnowledgeStartEvent.java
    │   ├── KnowledgeEndEvent.java
    │   ├── AgentStartEvent.java
    │   ├── AgentTextEvent.java
    │   ├── AgentToolStartEvent.java
    │   ├── AgentToolEndEvent.java
    │   ├── AgentDoneEvent.java
    │   ├── AgentErrorEvent.java
    │   ├── PlanCreatedEvent.java
    │   ├── PlanStepStartEvent.java
    │   ├── PlanStepEndEvent.java
    │   ├── PlanStepErrorEvent.java
    │   ├── ReferenceEvent.java
    │   ├── RecommendEvent.java
    │   ├── DoneEvent.java
    │   └── ErrorEvent.java
    └── config/
        └── EventBusConfiguration.java     # Spring 装配
```

模块依赖：`module-agent` → `module-event`, `module-web` → `module-event`

---

## 9. 前端协议变更

### 9.1 TypeScript 类型

```typescript
// 基础类型
interface AgentEvent {
  type: string;
  phase: AgentPhase;
  eventId: string;
  timestamp: number;
}

type AgentPhase =
  | 'THINKING'
  | 'RESPONDING'
  | 'EXECUTING'
  | 'KNOWLEDGE'
  | 'COLLABORATING'
  | 'PLANNING'
  | 'COMPLETED'
  | 'ERROR';

// 阶段事件
interface ThinkingStartEvent extends AgentEvent { type: 'thinking_start'; }
interface ThinkingTextEvent extends AgentEvent { type: 'thinking_text'; text: string; }
interface ThinkingEndEvent extends AgentEvent { type: 'thinking_end'; durationMs: number; }
interface TextDeltaEvent extends AgentEvent { type: 'text_delta'; text: string; }
interface ToolStartEvent extends AgentEvent {
  type: 'tool_start'; id: string; name: string; input: Record<string, unknown>;
}
interface ToolEndEvent extends AgentEvent {
  type: 'tool_end'; id: string; name: string; success: true; result: string;
}
interface ToolErrorEvent extends AgentEvent {
  type: 'tool_error'; id: string; name: string; error: string;
}

// 多 Agent 事件
interface AgentStartEvent extends AgentEvent {
  type: 'agent_start'; agentId: string; task: string;
}
interface AgentTextEvent extends AgentEvent {
  type: 'agent_text'; agentId: string; text: string;
}
interface AgentToolStartEvent extends AgentEvent {
  type: 'agent_tool_start'; agentId: string; id: string; name: string; input: Record<string, unknown>;
}
interface AgentToolEndEvent extends AgentEvent {
  type: 'agent_tool_end'; agentId: string; id: string; name: string; success: true; result: string;
}
interface AgentDoneEvent extends AgentEvent {
  type: 'agent_done'; agentId: string; result: string;
}
interface AgentErrorEvent extends AgentEvent {
  type: 'agent_error'; agentId: string; error: string;
}

// Plan 事件
interface PlanCreatedEvent extends AgentEvent {
  type: 'plan_created'; steps: Array<{id: string; title: string; instruction: string; order: number}>;
}
interface PlanStepStartEvent extends AgentEvent {
  type: 'plan_step_start'; stepId: string; title: string; instruction: string; order: number;
}
interface PlanStepEndEvent extends AgentEvent {
  type: 'plan_step_end'; stepId: string; title: string; result: string;
}
interface PlanStepErrorEvent extends AgentEvent {
  type: 'plan_step_error'; stepId: string; title: string; error: string;
}

// 终端事件
interface ReferenceEvent extends AgentEvent {
  type: 'reference'; items: Array<{url: string; title: string; content: string}>;
}
interface RecommendEvent extends AgentEvent {
  type: 'recommend'; questions: string[];
}
interface DoneEvent extends AgentEvent {
  type: 'done';
  conversationId: string;
  totalDurationMs: number;
  roundCount: number;
  usedTools: string[];
  referenceCount: number;
  recommendations: string | null;
}
interface ErrorEvent extends AgentEvent {
  type: 'error'; conversationId: string; message: string; code: string;
}

// 联合类型
type AnyAgentEvent =
  | ThinkingStartEvent | ThinkingTextEvent | ThinkingEndEvent
  | TextDeltaEvent
  | ToolStartEvent | ToolEndEvent | ToolErrorEvent
  | AgentStartEvent | AgentTextEvent | AgentToolStartEvent | AgentToolEndEvent
  | AgentDoneEvent | AgentErrorEvent
  | PlanCreatedEvent | PlanStepStartEvent | PlanStepEndEvent | PlanStepErrorEvent
  | ReferenceEvent | RecommendEvent | DoneEvent | ErrorEvent;
```

### 9.2 前端处理简化

```typescript
// 旧：需要 JSON.parse content 字段
case 'agent_start': {
  const { agent, task } = JSON.parse(event.content);  // 二次解析
  ...
}

// 新：直接读取字段
case 'agent_start': {
  const { agentId, task } = event;  // 无需 JSON.parse
  ...
}
```

### 9.3 阶段驱动的 UI 渲染

```typescript
function AgentProcessPanel({ events }: { events: AnyAgentEvent[] }) {
  const phases = useMemo(() => {
    const thinkingEvents = events.filter(e => e.phase === 'THINKING');
    const toolEvents = events.filter(e => e.phase === 'EXECUTING');
    const agentEvents = events.filter(e => e.phase === 'COLLABORATING');
    const planEvents = events.filter(e => e.phase === 'PLANNING');
    // 每个阶段独立渲染模块
    return { thinkingEvents, toolEvents, agentEvents, planEvents };
  }, [events]);

  return (
    <div>
      {phases.thinkingEvents.length > 0 && <ThinkingPanel events={phases.thinkingEvents} />}
      {phases.planEvents.length > 0 && <PlanPanel events={phases.planEvents} />}
      {phases.agentEvents.length > 0 && <AgentPanel events={phases.agentEvents} />}
      {phases.toolEvents.length > 0 && <ToolPanel events={phases.toolEvents} />}
    </div>
  );
}
```

---

## 10. 迁移计划

### Phase 1：基础设施（2 天）

1. 创建 `module-event` 模块，定义全部 21 个事件类
2. 实现 `DefaultAgentEventBus` + `SseEventEmitter`
3. 编写单元测试验证事件序列化正确性
4. 在 `module-common` pom.xml 中添加 `module-event` 依赖

### Phase 2：Agent 改造（3 天）

1. `BaseAgent` — 移除 `AgentResponse` 依赖，注入 `AgentEventBus`
2. `DearAgent` — 替换全部 `sink.tryEmitNext(createXxx())` 为 `eventBus.publish(new XxxEvent())`
3. `WebSearchReactAgent` — 同上
4. `PlanExecuteAgent` — 替换 plan/step 事件
5. `SimpleOrchestrator` — 替换 agent 事件

### Phase 3：Controller & 持久化（1.5 天）

1. `AgentController` — 使用 `SseEventEmitter.toSseFlux()` 替代直接消费 agent Flux
2. `MultiAgentController` — 同上
3. `SessionEventPersister` — 从直接调用改为事件监听

### Phase 4：清理 & 前端（1.5 天）

1. 删除 `AgentResponse` 类（保留注释一周后删文件）
2. 更新 `docs/multi-agent-frontend-guide.md` 为新协议
3. 前端适配（如果前端在同一仓库）

### 风险控制

- **新旧并行**：Phase 2 期间保留 `AgentResponse`，Agent 改造完一个验证一个
- **回归测试**：每个 Agent 改造后手工跑一次完整对话流程
- **Sinks 保留**：Agent 内部仍可用 `Sinks` 做背压控制，`AgentEventBus` 包装它
- **回滚**：如果上线后前端解析异常，可快速回退到旧 `AgentResponse`（保留一周）

---

## 11. 验收标准

- [ ] 全部 21 个事件类定义完成，`toSseJson()` 输出符合规范
- [ ] `DefaultAgentEventBus` 支持按 type/phase/all 三种订阅模式
- [ ] `SseEventEmitter.toSseFlux()` 输出合法 SSE 格式
- [ ] `DearAgent` 对话完整流：thinking_start → thinking_text → thinking_end → text_delta → tool_start → tool_end → done
- [ ] `PlanExecuteAgent` 计划流：plan_created → plan_step_start → text_delta → plan_step_end → done
- [ ] `SimpleOrchestrator` 多 Agent 流：agent_start → agent_text → agent_done
- [ ] `SessionEventPersister` 正确监听事件并保存到数据库
- [ ] 旧 `AgentResponse` 类已删除，无残留引用
- [ ] SSE 输出 JSON 中不再出现 JSON-string-inside-JSON 的嵌套结构
