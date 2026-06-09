# 前端 SSE 事件协议变更需求

> 后端事件体系已从 `AgentResponse` 迁至 `AgentEventBus`，SSE 输出格式变更。以下为前端需要适配的全部改动。

---

## 1. 一句话总结

**`content` 字段消失，每种事件都有独立的类型化字段，无需 `JSON.parse(event.content)`。**

---

## 2. 事件 type 映射表

| 旧 type | 旧字段 | 新 type | 新字段 | 前端改动 |
|---------|--------|---------|--------|---------|
| `text` | `content: string` | `text_delta` | `text: string` | 改 type 名 + 改字段名 |
| `thinking` | `content: string` | `thinking_start` | 无额外字段 | 新增：打开思考面板 |
| | | `thinking_text` | `text: string` | 改 type 名 + 改字段名 |
| | | `thinking_end` | `durationMs: number` | 新增：关闭面板 + 显示耗时 |
| `function` | `content: string`（内含 JSON） | `tool_start` | `id, name, input: object` | 不再 `JSON.parse` |
| | | `tool_end` | `id, name, success, result` | 同上 |
| | | `tool_error` | `id, name, error` | 同上 |
| `mcp` | 同 function | 合并到 `tool_start/end/error` | 同上 | 删除 mcp 分支 |
| `skill` | 同 function | 合并到 `tool_start/end/error` | 同上 | 删除 skill 分支 |
| `reference` | `content: string`（JSON 数组） | `reference` | `items: Array<{url,title,content}>` | 不 `JSON.parse` |
| `recommend` | `content: string`（JSON 数组） | `recommend` | `questions: string[]` | 不 `JSON.parse` |
| `knowledge` | `content: string` | `knowledge_end` | `items, count` | 改 type，不 `JSON.parse` |
| `agent_start` | `content: string`（含 JSON） | `agent_start` | `agentId, task` | 不 `JSON.parse` |
| `agent_done` | `content: string`（含 JSON） | `agent_done` | `agentId, result` | 不 `JSON.parse` |
| `agent_error` | `content: string`（含 JSON） | `agent_error` | `agentId, error` | 不 `JSON.parse` |
| | | `agent_text` | `agentId, text` | **新增**：子 Agent 文本流 |
| | | `agent_tool_start` | `agentId, id, name, input` | **新增**：子 Agent 工具详情 |
| | | `agent_tool_end` | `agentId, id, success, result` | **新增**：子 Agent 工具详情 |
| `plan` | `content: string`（JSON 数组） | `plan_created` | `steps: PlanStep[]` | 改 type，不 `JSON.parse` |
| `plan_step_start` | `content: string`（含 JSON） | `plan_step_start` | `stepId, title, instruction, order` | 不 `JSON.parse` |
| `plan_step_done` | `content: string`（含 JSON） | `plan_step_end` | `stepId, title, result` | 改 type，不 `JSON.parse` |
| `plan_step_error` | `content: string` | `plan_step_error` | `stepId, title, error` | 不 `JSON.parse` |
| `done` | `content: string` | `done` | `conversationId, totalDurationMs, roundCount, usedTools` | 新增统计字段 |
| `error` | `content: string` | `error` | `conversationId, message, code` | 新增 code 字段 |

所有事件新增公共字段：`eventId`（string）、`timestamp`（number）、`phase`（AgentPhase）、`phaseName`（string）。

---

## 3. TypeScript 类型（可直接复制使用）

```typescript
// ===== event-types.ts =====

type AgentPhase = 'THINKING' | 'RESPONDING' | 'EXECUTING' | 'KNOWLEDGE'
  | 'COLLABORATING' | 'PLANNING' | 'COMPLETED' | 'ERROR';

interface BaseEvent {
  type: string;
  eventId: string;
  timestamp: number;
  phase: AgentPhase;
  phaseName: AgentPhase;
}

// Thinking
interface ThinkingStartEvent extends BaseEvent { type: 'thinking_start' }
interface ThinkingTextEvent extends BaseEvent { type: 'thinking_text'; text: string }
interface ThinkingEndEvent extends BaseEvent { type: 'thinking_end'; durationMs: number }

// Text
interface TextDeltaEvent extends BaseEvent { type: 'text_delta'; text: string }

// Tool
interface ToolStartEvent extends BaseEvent {
  type: 'tool_start'; id: string; name: string; input: Record<string, unknown>;
}
interface ToolEndEvent extends BaseEvent {
  type: 'tool_end'; id: string; name: string; success: boolean; result: string;
}
interface ToolErrorEvent extends BaseEvent {
  type: 'tool_error'; id: string; name: string; error: string;
}

// Multi-Agent
interface AgentStartEvent extends BaseEvent {
  type: 'agent_start'; agentId: string; task: string;
}
interface AgentTextEvent extends BaseEvent {
  type: 'agent_text'; agentId: string; text: string;
}
interface AgentToolStartEvent extends BaseEvent {
  type: 'agent_tool_start'; agentId: string; id: string; name: string; input: Record<string, unknown>;
}
interface AgentToolEndEvent extends BaseEvent {
  type: 'agent_tool_end'; agentId: string; id: string; name: string; success: boolean; result: string;
}
interface AgentDoneEvent extends BaseEvent {
  type: 'agent_done'; agentId: string; result: string;
}
interface AgentErrorEvent extends BaseEvent {
  type: 'agent_error'; agentId: string; error: string;
}

// Plan
interface PlanStep { id: string; title: string; instruction: string; order: number }
interface PlanCreatedEvent extends BaseEvent { type: 'plan_created'; steps: PlanStep[] }
interface PlanStepStartEvent extends BaseEvent {
  type: 'plan_step_start'; stepId: string; title: string; instruction: string; order: number;
}
interface PlanStepEndEvent extends BaseEvent {
  type: 'plan_step_end'; stepId: string; title: string; result: string;
}
interface PlanStepErrorEvent extends BaseEvent {
  type: 'plan_step_error'; stepId: string; title: string; error: string;
}

// Terminal
interface ReferenceEvent extends BaseEvent {
  type: 'reference';
  items: Array<{ url: string; title: string; content: string }>;
}
interface RecommendEvent extends BaseEvent {
  type: 'recommend'; questions: string[];
}
interface DoneEvent extends BaseEvent {
  type: 'done'; conversationId: string; totalDurationMs: number; roundCount: number; usedTools: string[];
}
interface ErrorEvent extends BaseEvent {
  type: 'error'; conversationId: string; message: string; code: string;
}

type AnyAgentEvent =
  | ThinkingStartEvent | ThinkingTextEvent | ThinkingEndEvent
  | TextDeltaEvent
  | ToolStartEvent | ToolEndEvent | ToolErrorEvent
  | AgentStartEvent | AgentTextEvent | AgentToolStartEvent | AgentToolEndEvent | AgentDoneEvent | AgentErrorEvent
  | PlanCreatedEvent | PlanStepStartEvent | PlanStepEndEvent | PlanStepErrorEvent
  | ReferenceEvent | RecommendEvent | DoneEvent | ErrorEvent;
```

---

## 4. SSE 解析 → 事件处理

```typescript
function handleSSEEvent(line: string) {
  if (!line.startsWith('data: ')) return;
  const event: AnyAgentEvent = JSON.parse(line.slice(6));

  switch (event.type) {
    // ── Thinking ──
    case 'thinking_start':
      openThinkingPanel();           // 展开可折叠区域
      break;
    case 'thinking_text':
      appendToThinkingPanel(event.text);  // 追加文本片段
      break;
    case 'thinking_end':
      closeThinkingPanel(event.durationMs);   // 折叠 + 显示耗时 "思考耗时 1.2s"
      break;

    // ── Text ──
    case 'text_delta':
      appendToAnswer(event.text);
      break;

    // ── Tool ──
    case 'tool_start':
      addToolCard(event.id, { name: event.name, input: event.input, status: 'running' });
      break;
    case 'tool_end':
      updateToolCard(event.id, { status: 'done', result: event.result });
      break;
    case 'tool_error':
      updateToolCard(event.id, { status: 'error', error: event.error });
      break;

    // ── Multi-Agent ──
    case 'agent_start':
      addAgentEntry(event.agentId, { task: event.task, status: 'running' });
      break;
    case 'agent_text':
      appendAgentOutput(event.agentId, event.text);
      break;
    case 'agent_tool_start':
      addAgentToolCard(event.agentId, event.id, { name: event.name, input: event.input });
      break;
    case 'agent_tool_end':
      updateAgentToolCard(event.agentId, event.id, { status: 'done', result: event.result });
      break;
    case 'agent_done':
      markAgentEntry(event.agentId, { status: 'done', result: event.result });
      break;
    case 'agent_error':
      markAgentEntry(event.agentId, { status: 'error', error: event.error });
      break;

    // ── Plan ──
    case 'plan_created':
      setPlanSteps(event.steps);     // 渲染步骤列表
      break;
    case 'plan_step_start':
      updateStepStatus(event.stepId, 'running');
      break;
    case 'plan_step_end':
      updateStepStatus(event.stepId, 'done');
      break;
    case 'plan_step_error':
      updateStepStatus(event.stepId, 'error');
      break;

    // ── Reference / Recommend ──
    case 'reference':
      setReferences(event.items);
      break;
    case 'recommend':
      setRecommendQuestions(event.questions);
      break;

    // ── Terminal ──
    case 'done':
      markStreamComplete(event.conversationId);
      break;
    case 'error':
      showErrorBanner(event.message, event.code);
      break;

    default:
      break; // 静默忽略未知 type
  }
}
```

---

## 5. 新旧代码对比（核心改动）

```typescript
// ===== 旧代码 =====
case 'agent_start': {
  const { agent, task } = JSON.parse(event.content);  // ❌ JSON.parse
  addAgentTask({ agent, status: 'running', task });
  break;
}
case 'reference': {
  setReferences(JSON.parse(event.content));            // ❌ JSON.parse
  break;
}
case 'function': {
  const { tool, status, args } = JSON.parse(event.content);  // ❌ JSON.parse
  updateToolStatus(tool, status, args);
  break;
}
case 'done': {
  finishStream(event.content);  // content = conversationId
  break;
}

// ===== 新代码 =====
case 'agent_start': {
  addAgentEntry(event.agentId, { task: event.task, status: 'running' });  // ✅ 直接读
  break;
}
case 'reference': {
  setReferences(event.items);              // ✅ 直接读
  break;
}
case 'tool_start': {
  addToolCard(event.id, { name: event.name, input: event.input, status: 'running' });
  break;
}
case 'tool_end': {
  updateToolCard(event.id, { status: 'done', result: event.result });
  break;
}
case 'done': {
  finishStream(event.conversationId);       // ✅ 字段独立
  break;
}
```

---

## 6. 历史消息渲染（重要）

历史消息 API (`GET /session/{id}/messages`) 的 assistant 消息新增 `eventStream` 字段，结构与实时 SSE 事件完全一致。

```json
{
  "id": 456,
  "messageType": "assistant",
  "eventStream": [
    {"type":"thinking_start","eventId":"e1","timestamp":1700000000000,"phase":"THINKING","phaseName":"THINKING"},
    {"type":"thinking_text","text":"让我分析..."},
    {"type":"thinking_end","durationMs":1200},
    {"type":"tool_start","id":"c1","name":"tavily_search","input":{"query":"AI"}},
    {"type":"tool_end","id":"c1","name":"tavily_search","success":true,"result":"..."},
    {"type":"text_delta","text":"根据搜索结果，2026年AI领域..."},
    {"type":"reference","items":[{"url":"...","title":"...","content":"..."}]},
    {"type":"recommend","questions":["..."]},
    {"type":"done","conversationId":"conv-abc","totalDurationMs":5200,"roundCount":2,"usedTools":["tavily_search"]}
  ]
}
```

**渲染方式**：将 `eventStream` 数组遍历推入同一个 `handleSSEEvent` 处理函数即可。

```typescript
// 历史消息组件
function HistoryMessage({ msg }: { msg: MessageVO }) {
  useEffect(() => {
    if (msg.eventStream) {
      msg.eventStream.forEach(event => handleSSEEvent_internal(event));
    }
  }, [msg.id]);
  // ...
}
```

这样**实时流和历史渲染走同一条路径，UI 天然一致**。

---

## 7. 改动清单

| 序号 | 文件 | 改动 | 预计 |
|------|------|------|------|
| 1 | 新建 `event-types.ts` | 复制第三节类型定义 | 5min |
| 2 | 修改 `sse-handler.ts` | 旧 `switch(type)` → 新 handler | 2h |
| 3 | 修改 `history-message.tsx` | `eventStream` 数组回放 → 同一 handler | 0.5h |
| 4 | 删除 `JSON.parse(event.content)` | 全项目搜索替换 | 0.5h |
| 5 | 新增 Thinking Panel 组件 | 阶段驱动展开/折叠 | 2h（可选） |
| 6 | 新增 Tool Card 组件 | tool_start → 卡片 | 2h（可选） |
| 7 | 新增 Agent Panel 组件 | 子 Agent 状态展示 | 2h（可选） |
| 8 | 回归测试 | 完整对话 + 历史加载 + 多 Agent | 2h |

**基础改动 3.5h，UI 增强另计。**

---

## 8. 兼容注意事项

1. **新旧协议并存期**：后端同时输出新旧两种事件，前端可以先加新分支再删旧分支
2. **未知 type**：`default` 分支静默跳过，不要 warn/error
3. **`eventStream` 可能为 null**：历史消息的 `eventStream` 字段建议判空，回退到旧 `content` 字段渲染
4. **API URL 无变化**：`/agent/chat/stream` 和 `/multi-agent/collaborate/stream` 路径不变
