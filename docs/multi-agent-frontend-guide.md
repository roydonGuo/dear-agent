# 多 Agent 协同 — 前端接入指南

> 基于 dear-agent Phase 1 Agent-as-Tool 实现，后端已就绪，前端可平行接入。

---

## 1. 两条对话路径

| 路径 | URL | 场景 |
|------|-----|------|
| 单 Agent 对话（保留不变） | `GET /agent/chat/stream` | 日常聊天、简单问答、文件操作 |
| 多 Agent 协同（新增） | `GET /multi-agent/collaborate/stream` | 复杂任务、需多 Agent 协作、深度研究 |

两条路径的 SSE 协议完全兼容，前端可以按同一种方式消费事件流。

---

## 2. 多 Agent 协同 API

### 2.1 发起协作对话

```
GET /multi-agent/collaborate/stream
```

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 用户问题 |
| `conversationId` | string | 是 | 会话 ID |
| `mode` | string | 否 | 协作模式：`auto`（默认，LLM 自主调度 Agent）\| `plan_execute`（先制定计划再逐步执行） |
| `think` | boolean | 否 | 是否启用深度思考 |
| `fileIds` | string | 否 | 关联文件 ID（逗号分隔） |

**示例：**

```js
// 自动调度模式
const url1 = `/multi-agent/collaborate/stream?query=${q}&conversationId=${cid}&mode=auto`;

// 计划执行模式（适合深度研究、多步骤分析）
const url2 = `/multi-agent/collaborate/stream?query=${q}&conversationId=${cid}&mode=plan_execute`;
```

### 2.2 停止协作

```
GET /multi-agent/stop?conversationId={conversationId}
```

**响应：**
```json
{ "code": 200, "success": true, "message": "已停止执行" }
```

---

## 3. SSE 事件流协议

### 3.1 已有事件类型（单 Agent 路径已有，继续沿用）

| type | content 结构 | 说明 |
|------|-------------|------|
| `text` | `string` | 文本增量输出 |
| `thinking` | `string` | 模型思考过程 |
| `reference` | `array` | 搜索引用列表 |
| `recommend` | `array` | 推荐问题列表 |
| `knowledge` | `array` | 知识库检索结果 |
| `function` | `string (JSON)` | 函数工具调用状态 |
| `mcp` | `string (JSON)` | MCP 工具调用状态 |
| `skill` | `string (JSON)` | Skill 工具调用状态 |
| `error` | `string` | 错误信息 |
| `done` | `string` | 流结束（content 为 conversationId） |

### 3.2 新增事件类型（Phase 1 多 Agent 专用）

#### `agent_start` — 子 Agent 开始执行

```json
{
  "type": "agent_start",
  "content": "{\"agent\":\"web_search_agent\",\"status\":\"start\",\"task\":\"搜索2026年AI发展最新动态\"}"
}
```

| 字段 | 说明 |
|------|------|
| `agent` | 被调度的 Agent 名称 |
| `status` | 固定 `"start"` |
| `task` | 分配给该 Agent 的任务描述 |

#### `agent_done` — 子 Agent 执行完成

```json
{
  "type": "agent_done",
  "content": "{\"agent\":\"web_search_agent\",\"status\":\"done\",\"result\":\"搜索结果显示...\"}"
}
```

| 字段 | 说明 |
|------|------|
| `agent` | Agent 名称 |
| `status` | 固定 `"done"` |
| `result` | Agent 的执行结果文本（可能较长） |

#### `agent_error` — 子 Agent 执行失败

```json
{
  "type": "agent_error",
  "content": "{\"agent\":\"web_search_agent\",\"status\":\"error\",\"error\":\"连接超时\"}"
}
```

#### `agent_call` — Agent 调用（fallback）

当工具调用解析异常时使用，正常情况下前端收到的是 `agent_start` / `agent_done`。

#### `plan` — Plan-Execute Agent 生成的执行计划

当 `mode=plan_execute` 时，Agent 先生成计划再逐步执行。

```json
{
  "type": "plan",
  "content": "[{\"id\":\"1\",\"instruction\":\"搜索AI最新进展\",\"order\":1},{\"id\":\"2\",\"instruction\":\"搜索AI公司\",\"order\":1}]"
}
```

`content` 为 JSON 数组，每项：`id`（步骤编号）、`title`（标题）、`instruction`（执行指令）、`order`（执行顺序，同 order 可并行）。

#### `plan_step_start` — 计划步骤开始执行

```json
{
  "type": "plan_step_start",
  "content": "{\"stepId\":\"1\",\"title\":\"搜索AI最新进展\",\"instruction\":\"调用web_search_agent...\",\"order\":1}"
}
```

#### `plan_step_done` — 计划步骤执行完成

```json
{
  "type": "plan_step_done",
  "content": "{\"stepId\":\"1\",\"title\":\"搜索AI最新进展\",\"result\":\"搜索结果显示...\"}"
}
```

#### `plan_step_error` — 计划步骤执行失败

```json
{
  "type": "plan_step_error",
  "content": "{\"stepId\":\"1\",\"title\":\"搜索AI最新进展\",\"error\":\"执行超时\"}"
}
```

---

## 4. 完整 SSE 流示例

多 Agent 协同场景下的一条典型 SSE 流：

```
data: {"type":"text","content":"让我来深入研究这个问题。"}

data: {"type":"agent_start","content":"{\"agent\":\"web_search_agent\",\"status\":\"start\",\"task\":\"搜索2026年AI发展趋势\"}"}

data: {"type":"text","content":"根据搜索结果，2026年AI领域主要有以下趋势..."}
data: {"type":"text","content":"1. 多模态大模型成为主流\n2. ..."}

data: {"type":"reference","content":[...],"count":5}

data: {"type":"agent_done","content":"{\"agent\":\"web_search_agent\",\"status\":\"done\",\"result\":\"...\"}"}

data: {"type":"agent_start","content":"{\"agent\":\"web_search_agent\",\"status\":\"start\",\"task\":\"搜索AI在医疗领域的应用\"}"}

data: {"type":"text","content":"在医疗领域..."}
data: {"type":"reference","content":[...],"count":3}

data: {"type":"agent_done","content":"{\"agent\":\"web_search_agent\",\"status\":\"done\",\"result\":\"...\"}"}

data: {"type":"text","content":"综合以上信息，我的结论如下：..."}
data: {"type":"recommend","content":["AI对就业市场的影响","如何入门AI开发","AI在金融领域的应用"],"count":3}

data: {"type":"done","content":"conv-uuid-xxx"}
```

### 4.2 `mode=plan_execute` 完整 SSE 流示例

```
data: {"type":"plan","content":"[{\"id\":\"1\",\"title\":\"搜索AI最新进展\",\"instruction\":\"...\",\"order\":1},{\"id\":\"2\",\"title\":\"搜索主要AI公司\",\"instruction\":\"...\",\"order\":1},{\"id\":\"3\",\"title\":\"综合生成报告\",\"instruction\":\"...\",\"order\":2}]"}

data: {"type":"plan_step_start","content":"{\"stepId\":\"1\",\"title\":\"搜索AI最新进展\",\"order\":1}"}
data: {"type":"text","content":"2026年AI领域的最新进展包括..."}
data: {"type":"reference","content":[...],"count":3}
data: {"type":"plan_step_done","content":"{\"stepId\":\"1\",\"title\":\"搜索AI最新进展\",\"result\":\"...\"}"}

data: {"type":"plan_step_start","content":"{\"stepId\":\"2\",\"title\":\"搜索主要AI公司\",\"order\":1}"}
data: {"type":"text","content":"主要的AI公司有..."}
data: {"type":"plan_step_done","content":"{\"stepId\":\"2\",\"title\":\"搜索主要AI公司\",\"result\":\"...\"}"}

data: {"type":"plan_step_start","content":"{\"stepId\":\"3\",\"title\":\"综合生成报告\",\"order\":2}"}
data: {"type":"text","content":"# AI领域深度分析报告\n\n## 最新进展\n..."}
data: {"type":"plan_step_done","content":"{\"stepId\":\"3\",\"title\":\"综合生成报告\",\"result\":\"...\"}"}

data: {"type":"done","content":"conv-uuid-xxx"}
```

---

## 5. 前端实现参考

### 5.1 SSE 事件解析

核心消费逻辑与单 Agent 路径**完全相同**，只需增加对新类型的处理：

```typescript
interface SSEEvent {
  type: string;
  content: string;
  count?: number;
}

function parseSSELine(line: string): SSEEvent | null {
  if (!line.startsWith('data: ')) return null;
  try {
    return JSON.parse(line.slice(6));
  } catch {
    return null;
  }
}

function handleSSEEvent(event: SSEEvent) {
  switch (event.type) {
    case 'text':
      // 追加到回答文本
      appendToAnswer(event.content);
      break;

    case 'thinking':
      // 追加到思考面板（如果开启了思考模式）
      appendToThinking(event.content);
      break;

    case 'reference':
      // 更新引用列表
      setReferences(JSON.parse(event.content));
      break;

    case 'recommend':
      // 更新推荐问题
      setRecommendations(JSON.parse(event.content));
      break;

    case 'agent_start': {
      // 新增：子 Agent 开始执行
      const { agent, task } = JSON.parse(event.content);
      addAgentTask({ agent, status: 'running', task });
      break;
    }

    case 'agent_done': {
      // 新增：子 Agent 执行完成
      const { agent, result } = JSON.parse(event.content);
      updateAgentTask(agent, { status: 'done', result });
      break;
    }

    case 'agent_error': {
      // 新增：子 Agent 执行失败
      const { agent, error } = JSON.parse(event.content);
      updateAgentTask(agent, { status: 'error', error });
      break;
    }

    case 'plan': {
      // 新增（mode=plan_execute）：收到执行计划
      const steps = JSON.parse(event.content);
      setPlanSteps(steps); // 渲染计划步骤面板
      break;
    }

    case 'plan_step_start': {
      // 新增：某个计划步骤开始执行
      const { stepId, title } = JSON.parse(event.content);
      updatePlanStep(stepId, { status: 'running', title });
      break;
    }

    case 'plan_step_done': {
      // 新增：某个计划步骤执行完成
      const { stepId, result } = JSON.parse(event.content);
      updatePlanStep(stepId, { status: 'done', result });
      break;
    }

    case 'plan_step_error': {
      // 新增：某个计划步骤执行失败
      const { stepId, error } = JSON.parse(event.content);
      updatePlanStep(stepId, { status: 'error', error });
      break;
    }

    case 'function':
    case 'mcp':
    case 'skill':
      // 已有：工具调用状态，可在工具面板展示
      updateToolStatus(JSON.parse(event.content));
      break;

    case 'error':
      // 全局错误
      showError(event.content);
      break;

    case 'done':
      // 流结束
      finishStream(event.content); // content 为 conversationId
      break;
  }
}
```

### 5.2 前端组件对接

**单 Agent 路径（无须改动）：**

```typescript
// 现有代码，保持不变
const url = `/agent/chat/stream?query=${q}&conversationId=${cid}&think=${think}`;
```

**多 Agent 路径（新增）：**

```typescript
// 仅 URL 不同，SSE 消费逻辑完全复用
const url = `/multi-agent/collaborate/stream?query=${q}&conversationId=${cid}&mode=auto`;
```

> 两种路径返回的 SSE 格式完全兼容，建议前端抽象一个 `useAgentStream(url)` hook，两条路径共用。

### 5.3 多 Agent 过程面板（可选 UI 增强）

#### mode=auto：Agent 调度过程

```
┌─────────────────────────────────────┐
│  用户：帮我研究一下AI最新进展         │
├─────────────────────────────────────┤
│  ⏳ web_search_agent 工作中...       │  ← agent_start
│     "搜索2026年AI发展趋势"            │
│  ✅ web_search_agent 完成            │  ← agent_done
│                                     │
│  ⏳ web_search_agent 工作中...       │
│     "搜索AI在医疗领域的应用"           │
│  ✅ web_search_agent 完成            │
│                                     │
│  📝 综合以上信息，结论如下：...        │  ← text 流式输出
└─────────────────────────────────────┘
```

#### mode=plan_execute：计划步骤过程

```
┌─────────────────────────────────────┐
│  用户：写一份AI行业深度分析报告        │
├─────────────────────────────────────┤
│  📋 执行计划                   ← plan│
│  ┌─────────────────────────────────┐│
│  │ ⏳ 1. 搜索AI最新进展             ││ ← plan_step_start
│  │ ✅ 1. 搜索AI最新进展       已完成 ││ ← plan_step_done
│  │ ⏳ 2. 搜索AI公司格局             ││
│  │ ... 搜索结果显示...              ││ ← text 在步骤间流式输出
│  │ ✅ 2. 搜索AI公司格局       已完成 ││
│  │ ⏳ 3. 综合生成分析报告           ││
│  │ ... 生成报告中...                ││
│  │ ✅ 3. 综合生成分析报告     已完成 ││
│  └─────────────────────────────────┘│
│                                     │
│  📝 # AI行业深度分析报告             │ ← 最终总结 text 流式输出
│     ## 最新进展...                   │
└─────────────────────────────────────┘
```

### 5.4 未识别 type 的处理策略

前端应遵循**渐进增强**原则：忽略不认识的 `type` 即可，不报错、不阻断。

```typescript
default:
  // 未知 type，静默忽略（未来扩展的新类型不会导致前端崩溃）
  break;
```

---

## 6. 与现有接口的兼容性

| 关注点 | 保证 |
|--------|------|
| 原有 `/agent/chat/stream` | **零改动**，SSE 协议、事件格式、会话持久化全部不变 |
| 新 `/multi-agent/collaborate/stream` | 独立路径，独立 Controller，不影响原有逻辑 |
| SSE type 兼容 | 新增的 `agent_start`/`agent_done`/`agent_error` 为可选事件，旧前端忽略不报错 |
| 会话数据 | 两种路径共用同一套 chat_conversation + chat_message 表 |
| 停止任务 | 各自独立：`/agent/stop` 和 `/multi-agent/stop` |

---

## 7. 当前可用的子 Agent

| Agent 名称 | 角色 | 能力 |
|-----------|------|------|
| `web_search_agent` | search | 联网搜索最新信息、验证事实、查找资料 |
| `plan_execute_agent` | plan | 将复杂任务拆解为分步计划并逐一执行，适合深度研究报告 |

未来新增 Agent 后，前端通过 `agent_start` 事件中的 `agent` 字段即可识别，无需额外适配。

---

## 8. 前端接入检查清单

**基础接入：**
- [ ] 新建 `useMultiAgentStream` hook（或复用现有 hook，只换 URL）
- [ ] 增加 `agent_start`、`agent_done`、`agent_error` 三种 SSE type 的 case 处理
- [ ] `default` 分支静默忽略未知 type（不要 warn/error）
- [ ] 调用 `GET /multi-agent/collaborate/stream` 传入 `query` + `conversationId` + `mode=auto`
- [ ] 停止按钮对接 `GET /multi-agent/stop?conversationId=xxx`
- [ ] 验证原有单 Agent 对话功能不受影响（回归测试）
- [ ] 验证 `mode=auto` 路径的完整流（text、agent_start/done、reference、recommend、done）

**Plan-Execute 模式（可选）：**
- [ ] 增加 `plan` SSE type，渲染计划步骤列表
- [ ] 增加 `plan_step_start`、`plan_step_done`、`plan_step_error` 三种 SSE type
- [ ] 切换 `mode=plan_execute` 验证完整流程
- [ ] （可选）实现计划步骤进度面板（展示每个 step 的状态和结果）

**过程面板（可选）：**
- [ ] mode=auto：实时展示 Agent 调度状态（工作中 / 完成 / 失败）
- [ ] mode=plan_execute：展示计划步骤及每步执行状态

---

如需后端配合调整 SSE 事件格式或新增字段，直接沟通即可。
