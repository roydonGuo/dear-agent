# 前端 SSE 事件协议迁移指南

> 后端已全面重构事件体系：从 `AgentResponse` 原始字符串模型升级为分阶段、结构化事件总线。本文档说明前端需要的改动。

---

## 1. 变更概览

### 1.1 核心变化

| 维度 | 旧协议 | 新协议 |
|------|--------|--------|
| 事件结构 | `{type, content, count?, data?}` | `{type, phase, eventId, timestamp, ...typedFields}` |
| 工具状态 | `content` 内嵌 JSON 字符串 | `tool_start` / `tool_end` 独立 type，字段类型化 |
| 思考过程 | `thinking` type，无生命周期 | `thinking_start` → `thinking_text` → `thinking_end` |
| 文本输出 | `text` type | `text_delta` type |
| 阶段感知 | 无 | 每个事件带 `phase` 字段 |
| JSON 嵌套 | `content` 字段需要 `JSON.parse()` | 所有字段扁平化，无需二次解析 |
| 历史消息 | 多个独立字段拆开存 | `eventStream` 单一 JSON 数组，与实时流结构一致 |

### 1.2 不再需要改前端

- API URL **不变**：`GET /agent/chat/stream` 和 `GET /multi-agent/collaborate/stream`
- SSE 传输格式 **不变**：仍然是 `data: {...}\n\n`
- 停止接口 **不变**：`GET /agent/stop`
- 会话列表接口 **不变**

---

## 2. SSE 事件协议 (新)

### 2.1 TypeScript 类型定义

```typescript
// ===== 基础类型 =====

type AgentPhase =
  | 'THINKING'
  | 'RESPONDING'
  | 'EXECUTING'
  | 'KNOWLEDGE'
  | 'COLLABORATING'
  | 'PLANNING'
  | 'COMPLETED'
  | 'ERROR';

interface AgentEvent {
  type: string;
  phase: AgentPhase;
  eventId: string;
  timestamp: number;
}

// ===== THINKING 阶段 =====

interface ThinkingStartEvent extends AgentEvent {
  type: 'thinking_start';
}

interface ThinkingTextEvent extends AgentEvent {
  type: 'thinking_text';
  text: string;
}

interface ThinkingEndEvent extends AgentEvent {
  type: 'thinking_end';
  durationMs: number;
}

// ===== RESPONDING 阶段 =====

interface TextDeltaEvent extends AgentEvent {
  type: 'text_delta';
  text: string;
}

// ===== EXECUTING 阶段 =====

interface ToolStartEvent extends AgentEvent {
  type: 'tool_start';
  id: string;
  name: string;
  input: Record<string, unknown>;
}

interface ToolEndEvent extends AgentEvent {
  type: 'tool_end';
  id: string;
  name: string;
  success: true;
  result: string;
}

interface ToolErrorEvent extends AgentEvent {
  type: 'tool_error';
  id: string;
  name: string;
  error: string;
}

// ===== COLLABORATING 阶段（多 Agent） =====

interface AgentStartEvent extends AgentEvent {
  type: 'agent_start';
  agentId: string;
  task: string;
}

interface AgentTextEvent extends AgentEvent {
  type: 'agent_text';
  agentId: string;
  text: string;
}

interface AgentToolStartEvent extends AgentEvent {
  type: 'agent_tool_start';
  agentId: string;
  id: string;
  name: string;
  input: Record<string, unknown>;
}

interface AgentToolEndEvent extends AgentEvent {
  type: 'agent_tool_end';
  agentId: string;
  id: string;
  name: string;
  success: true;
  result: string;
}

interface AgentDoneEvent extends AgentEvent {
  type: 'agent_done';
  agentId: string;
  result: string;
}

interface AgentErrorEvent extends AgentEvent {
  type: 'agent_error';
  agentId: string;
  error: string;
}

// ===== PLANNING 阶段 =====

interface PlanCreatedEvent extends AgentEvent {
  type: 'plan_created';
  steps: PlanStep[];
}

interface PlanStep {
  id: string;
  title: string;
  instruction: string;
  order: number;
}

interface PlanStepStartEvent extends AgentEvent {
  type: 'plan_step_start';
  stepId: string;
  title: string;
  instruction: string;
  order: number;
}

interface PlanStepEndEvent extends AgentEvent {
  type: 'plan_step_end';
  stepId: string;
  title: string;
  result: string;
}

interface PlanStepErrorEvent extends AgentEvent {
  type: 'plan_step_error';
  stepId: string;
  title: string;
  error: string;
}

// ===== 终端事件 =====

interface ReferenceEvent extends AgentEvent {
  type: 'reference';
  items: SearchResultItem[];
}

interface SearchResultItem {
  url: string;
  title: string;
  content: string;
}

interface RecommendEvent extends AgentEvent {
  type: 'recommend';
  questions: string[];
}

interface DoneEvent extends AgentEvent {
  type: 'done';
  conversationId: string;
  totalDurationMs: number;
  roundCount: number;
  usedTools: string[];
}

interface ErrorEvent extends AgentEvent {
  type: 'error';
  conversationId: string;
  message: string;
  code: string;
}

// ===== 联合类型 =====

type AnyAgentEvent =
  | ThinkingStartEvent | ThinkingTextEvent | ThinkingEndEvent
  | TextDeltaEvent
  | ToolStartEvent | ToolEndEvent | ToolErrorEvent
  | AgentStartEvent | AgentTextEvent | AgentToolStartEvent | AgentToolEndEvent
  | AgentDoneEvent | AgentErrorEvent
  | PlanCreatedEvent | PlanStepStartEvent | PlanStepEndEvent | PlanStepErrorEvent
  | ReferenceEvent | RecommendEvent | DoneEvent | ErrorEvent;
```

### 2.2 事件生命周期流程

```
用户提问
  │
  ├─ THINKING 阶段 ──────────────────────────
  │   thinking_start          → 创建思考面板
  │   thinking_text (×N)     → 追加思考文本
  │   thinking_end            → 折叠面板，显示耗时
  │
  ├─ KNOWLEDGE 阶段 (可选) ──────────────────
  │   knowledge_end           → 展示检索结果
  │
  ├─ EXECUTING 阶段 ─────────────────────────
  │   tool_start              → 渲染工具卡片（运行中）
  │   tool_end                → 更新卡片（完成）
  │   tool_error              → 更新卡片（失败）
  │
  ├─ COLLABORATING 阶段 (可选) ──────────────
  │   agent_start             → 渲染子 Agent 条目
  │   agent_text (×N)         → 子 Agent 文本
  │   agent_done              → 标记子 Agent 完成
  │
  ├─ PLANNING 阶段 (可选) ───────────────────
  │   plan_created            → 渲染计划步骤列表
  │   plan_step_start         → 标记步骤运行中
  │   plan_step_end           → 标记步骤完成
  │
  ├─ RESPONDING 阶段 ────────────────────────
  │   text_delta (×N)         → 追加主回答文本
  │   reference               → 展示引用列表
  │   recommend               → 展示推荐问题
  │
  └─ COMPLETED ──────────────────────────────
      done                    → 流结束，显示统计
```

### 2.3 新旧事件 type 对照表

| 旧 type | 新 type | 变化说明 |
|---------|---------|---------|
| `text` | `text_delta` | 改名，字段从 `content` 改为 `text` |
| `thinking` | `thinking_start` / `thinking_text` / `thinking_end` | 拆分为三阶段，新增 `durationMs` |
| `function` | `tool_start` / `tool_end` / `tool_error` | 拆分，`content` 内嵌 JSON 改为扁平字段 |
| `mcp` | `tool_start` / `tool_end` (同 function) | 统一为 tool 事件，不再区分类型 |
| `skill` | `tool_start` / `tool_end` (同 function) | 同上 |
| `reference` | `reference` | 字段从 `content`(JSON字符串) 改为 `items`(数组) |
| `recommend` | `recommend` | 字段从 `content`(JSON字符串) 改为 `questions`(数组) |
| `done` | `done` | 新增 `totalDurationMs`、`roundCount`、`usedTools` |
| `error` | `error` | 新增 `code` 字段 |
| `knowledge` | `knowledge_end` | 字段从 `content`(JSON字符串) 改为 `items`(数组) |
| `agent_start` | `agent_start` | 字段从 `content`(JSON字符串) 改为 `agentId`、`task` |
| `agent_done` | `agent_done` | 同上，字段改为 `agentId`、`result` |
| `agent_error` | `agent_error` | 同上，字段改为 `agentId`、`error` |
| `plan` | `plan_created` | 改名，字段从 `content`(JSON字符串) 改为 `steps`(数组) |
| `plan_step_start` | `plan_step_start` | 字段扁平化：`stepId`、`title`、`instruction`、`order` |
| `plan_step_done` | `plan_step_end` | 改名，字段扁平化 |
| `plan_step_error` | `plan_step_error` | 字段扁平化 |
| — | `agent_text` | **新增**：子 Agent 文本增量 |
| — | `agent_tool_start` / `agent_tool_end` | **新增**：子 Agent 工具调用详情 |
| — | `thinking_start` / `thinking_end` | **新增**：思考阶段生命周期 |

---

## 3. 前端改造指南

### 3.1 核心事件处理器（替换旧 switch）

```typescript
function handleSSEEvent(raw: string): void {
  if (!raw.startsWith('data: ')) return;
  const event: AnyAgentEvent = JSON.parse(raw.slice(6));

  switch (event.type) {
    // ── Thinking ──
    case 'thinking_start':
      openThinkingPanel();
      break;
    case 'thinking_text':
      appendThinkingText(event.text);
      break;
    case 'thinking_end':
      closeThinkingPanel(event.durationMs);
      break;

    // ── Text ──
    case 'text_delta':
      appendAnswerText(event.text);
      break;

    // ── Tool ──
    case 'tool_start':
      addToolCard({ id: event.id, name: event.name, input: event.input, status: 'running' });
      break;
    case 'tool_end':
      updateToolCard(event.id, { status: 'done', result: event.result });
      break;
    case 'tool_error':
      updateToolCard(event.id, { status: 'error', error: event.error });
      break;

    // ── Multi-Agent ──
    case 'agent_start':
      addAgentEntry({ agentId: event.agentId, task: event.task, status: 'running' });
      break;
    case 'agent_text':
      appendAgentText(event.agentId, event.text);
      break;
    case 'agent_tool_start':
      addAgentToolCard(event.agentId, { id: event.id, name: event.name, input: event.input });
      break;
    case 'agent_tool_end':
      updateAgentToolCard(event.agentId, event.id, { status: 'done', result: event.result });
      break;
    case 'agent_done':
      updateAgentEntry(event.agentId, { status: 'done', result: event.result });
      break;
    case 'agent_error':
      updateAgentEntry(event.agentId, { status: 'error', error: event.error });
      break;

    // ── Plan ──
    case 'plan_created':
      renderPlanSteps(event.steps);
      break;
    case 'plan_step_start':
      updatePlanStep(event.stepId, { status: 'running' });
      break;
    case 'plan_step_end':
      updatePlanStep(event.stepId, { status: 'done', result: event.result });
      break;
    case 'plan_step_error':
      updatePlanStep(event.stepId, { status: 'error', error: event.error });
      break;

    // ── Reference / Recommend ──
    case 'reference':
      setReferences(event.items);
      break;
    case 'recommend':
      setRecommendations(event.questions);
      break;

    // ── Terminal ──
    case 'done':
      finishStream(event.conversationId);
      break;
    case 'error':
      showError(event.message, event.code);
      break;

    default:
      // 静默忽略未知 type（渐进增强）
      break;
  }
}
```

### 3.2 关键对比：旧代码 → 新代码

```typescript
// === 旧：需要 JSON.parse content ===
case 'agent_start': {
  const { agent, task } = JSON.parse(event.content);
  addAgentTask({ agent, status: 'running', task });
  break;
}
case 'reference': {
  const items = JSON.parse(event.content);
  setReferences(items);
  break;
}
case 'function': {
  const { tool, status, args } = JSON.parse(event.content);
  updateToolStatus(tool, status, args);
  break;
}

// === 新：直接读取字段 ===
case 'agent_start': {
  addAgentEntry({ agentId: event.agentId, task: event.task, status: 'running' });
  break;
}
case 'reference': {
  setReferences(event.items);
  break;
}
case 'tool_start': {
  addToolCard({ id: event.id, name: event.name, input: event.input, status: 'running' });
  break;
}
```

### 3.3 阶段驱动的 UI 组织

利用 `phase` 字段按阶段分组渲染，替代之前平铺的 type switch：

```typescript
function AgentMessage({ events }: { events: AnyAgentEvent[] }) {
  const { thinking, executing, collaborating, planning, responding } = useMemo(() => ({
    thinking:   events.filter(e => e.phase === 'THINKING'),
    executing:  events.filter(e => e.phase === 'EXECUTING'),
    collaborating: events.filter(e => e.phase === 'COLLABORATING'),
    planning:   events.filter(e => e.phase === 'PLANNING'),
    responding: events.filter(e => e.phase === 'RESPONDING'),
  }), [events]);

  return (
    <div className="agent-message">
      {thinking.length > 0 && <ThinkingPanel events={thinking} />}
      {planning.length > 0 && <PlanPanel events={planning} />}
      {collaborating.length > 0 && <AgentPanel events={collaborating} />}
      {executing.length > 0 && <ToolPanel events={executing} />}
      {responding.length > 0 && <RespondingPanel events={responding} />}
    </div>
  );
}
```

---

## 4. 历史消息渲染（统一路径）

### 4.1 历史消息 API 变更

```
GET /session/{id}/messages
```

新增字段 `eventStream`，与实时 SSE 事件结构完全一致：

```json
{
  "messages": [
    {
      "id": 456,
      "messageType": "assistant",
      "eventStream": [
        {"type":"thinking_start","phase":"THINKING","eventId":"e1","timestamp":1700000000000},
        {"type":"thinking_text","phase":"THINKING","text":"让我分析..."},
        {"type":"thinking_end","phase":"THINKING","durationMs":1200},
        {"type":"tool_start","phase":"EXECUTING","id":"c1","name":"tavily_search","input":{"query":"AI"}},
        {"type":"tool_end","phase":"EXECUTING","id":"c1","name":"tavily_search","success":true,"result":"..."},
        {"type":"text_delta","phase":"RESPONDING","text":"根据搜索结果"},
        {"type":"reference","phase":"RESPONDING","items":[{"url":"...","title":"...","content":"..."}]},
        {"type":"recommend","phase":"RESPONDING","questions":["..."]},
        {"type":"done","phase":"COMPLETED","conversationId":"xxx","totalDurationMs":3200,"roundCount":2}
      ],
      "createTime": "2026-06-09T16:00:00"
    }
  ]
}
```

### 4.2 实时流 = 历史渲染（同一组件）

```typescript
// 实时 SSE 流
function StreamChat() {
  const [events, setEvents] = useState<AnyAgentEvent[]>([]);

  useEffect(() => {
    const es = new EventSource(`/agent/chat/stream?query=${q}&conversationId=${cid}`);
    es.onmessage = (msg) => {
      const event: AnyAgentEvent = JSON.parse(msg.data);
      setEvents(prev => [...prev, event]);
    };
    return () => es.close();
  }, []);

  return <AgentMessage events={events} />;   // ← 统一渲染组件
}

// 历史消息
function HistoryChat({ message }: { message: MessageVO }) {
  const [events, setEvents] = useState<AnyAgentEvent[]>([]);

  useEffect(() => {
    if (message.eventStream) {
      setEvents(message.eventStream as AnyAgentEvent[]);
    }
  }, [message.id]);

  return <AgentMessage events={events} />;   // ← 同一个组件！
}
```

---

## 5. 兼容性策略

### 5.1 新旧协议共存期

后端同时输出新旧两种事件格式，前端可以渐进迁移：

```
旧 type: text, thinking, reference, recommend, function, mcp, skill, done, error
新 type: text_delta, thinking_start/text/end, tool_start/end/error, reference, recommend, done, error
```

**建议**：先加新 type 处理，稳定后删除旧 type 处理。

### 5.2 识别新旧消息

```typescript
function isNewProtocol(message: MessageVO): boolean {
  return !!message.eventStream;
}

function isOldProtocol(message: MessageVO): boolean {
  return !message.eventStream && !!message.content;
}
```

### 5.3 未识别 type 静默忽略

```typescript
default:
  // 不要 warn、不要 error，静默跳过
  break;
```

---

## 6. 改动量预估

| 改动项 | 工作量 | 说明 |
|--------|--------|------|
| 新增 TypeScript 类型文件 | 0.5h | 复制本文档第 2.1 节即可 |
| 替换 SSE 事件处理 switch | 2h | 旧 handler 替换为新 handler |
| 历史消息渲染接入 | 1h | `eventStream` 数组遍历 → 同一组件 |
| 阶段面板 UI（可选） | 4h | Thinking/Tool/Agent/Plan 面板组件 |
| 回归测试 | 2h | 完整对话流程 + 历史加载 |
| **合计** | **5.5h ~ 9.5h** | |

---

## 7. 完整 SSE 流示例

```
data: {"type":"thinking_start","phase":"THINKING","eventId":"e1","timestamp":1718000000000}

data: {"type":"thinking_text","phase":"THINKING","eventId":"e2","timestamp":1718000000100,"text":"让我分析这个问题..."}

data: {"type":"thinking_text","phase":"THINKING","eventId":"e3","timestamp":1718000000200,"text":"需要搜索最新信息"}

data: {"type":"thinking_end","phase":"THINKING","eventId":"e4","timestamp":1718000003000,"durationMs":3000}

data: {"type":"tool_start","phase":"EXECUTING","eventId":"e5","timestamp":1718000003100,"id":"call_01","name":"tavily_search","input":{"query":"2026年AI发展趋势"}}

data: {"type":"tool_end","phase":"EXECUTING","eventId":"e6","timestamp":1718000008500,"id":"call_01","name":"tavily_search","success":true,"result":"搜索结果显示..."}

data: {"type":"text_delta","phase":"RESPONDING","eventId":"e7","timestamp":1718000008600,"text":"根据"}

data: {"type":"text_delta","phase":"RESPONDING","eventId":"e8","timestamp":1718000008700,"text":"搜索结果，2026年AI领域"}

data: {"type":"text_delta","phase":"RESPONDING","eventId":"e9","timestamp":1718000008800,"text":"主要有以下趋势：\n1. ..."}

data: {"type":"reference","phase":"RESPONDING","eventId":"e10","timestamp":1718000008900,"items":[{"url":"https://...","title":"AI Trends 2026","content":"..."}]}

data: {"type":"recommend","phase":"RESPONDING","eventId":"e11","timestamp":1718000009000,"questions":["AI对就业市场的影响","如何入门AI开发"]}

data: {"type":"done","phase":"COMPLETED","eventId":"e12","timestamp":1718000009100,"conversationId":"conv-abc123","totalDurationMs":9100,"roundCount":2,"usedTools":["tavily_search"]}
```

---

> 有问题随时找后端对齐，事件协议以本文档为准。
