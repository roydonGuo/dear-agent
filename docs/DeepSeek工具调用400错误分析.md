# DeepSeek 模型工具调用 400 错误分析

## 问题现象

配置 DeepSeek 模型后，调用对话接口让 AI 调用工具（tool calling）时报 400 Bad Request：

```
org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest: 
  400 Bad Request from POST https://api.deepseek.com/v1/chat/completions
```

普通对话（不触发工具调用）正常，但一旦模型需要调用工具就报错。

## 根因分析

Spring AI 的 `OpenAiChatModel` 以 OpenAI API 协议为标准构建请求，但 **DeepSeek 的 API 在工具调用场景下与 OpenAI 存在多处不兼容**。

### 原因一：`tool_choice` 参数缺失（最可能）

OpenAI API 在 tools 存在时默认 `tool_choice` 为 `"auto"`。DeepSeek **要求显式传递** `tool_choice` 参数，否则返回 400。

Spring AI 1.1.0 的 `OpenAiApi` 在构建请求时，未默认设置 `tool_choice`：

```
// Spring AI 实际发出的请求（缺失 tool_choice）
{
  "model": "deepseek-chat",
  "messages": [...],
  "tools": [{...}],
  "stream": true
  // ← 缺少 "tool_choice": "auto"
}
```

### 原因二：流式工具调用（Streaming + Tool Calls）兼容性

从堆栈跟踪看，错误发生在流式响应聚合阶段：

```
OpenAiApi.chatCompletionStream → MessageAggregator.aggregate → 400 Bad Request
```

DeepSeek 对流式工具调用的处理方式与 OpenAI 存在差异：

- **OpenAI**：流式返回增量 tool_calls，每个 chunk 的 `tool_calls[].function.arguments` 是增量片段，客户端需要拼接
- **DeepSeek**：对于工具调用场景，流式返回的数据结构或时序可能与 OpenAI 不同，导致 Spring AI 的 `MessageAggregator` 解析失败

部分 DeepSeek 模型版本甚至**不支持流式工具调用**，会直接在 HTTP 层面返回 400。

### 原因三：并行工具调用不支持

`DearAgent.executeToolCalls()` 方法将多个工具调用**并行执行**（通过 `Schedulers.boundedElastic()`），然后汇总为单条 `ToolResponseMessage`：

```java
// DearAgent.java:329
List<ToolResponseMessage.ToolResponse> sortedResponses = new ArrayList<>();
for (AssistantMessage.ToolCall tc : originalToolCalls) {
    sortedResponses.add(responseMap.get(tc.id()));
}
messages.add(ToolResponseMessage.builder().responses(sortedResponses).build());
```

DeepSeek API 严格限制**每个 assistant 消息只能包含一个 tool_call**，不支持 OpenAI 那样的多工具并行调用。当一个 assistant 消息包含多个 `tool_calls` 时，DeepSeek 可能返回 400。

### 原因四：消息格式严格要求

DeepSeek 对工具调用后的消息格式校验更严格，要求对话历史严格遵循：

```
user → assistant(tool_calls) → tool(tool_call_id 必须匹配)
```

任何偏离（如 tool_call_id 不匹配、消息顺序不对、额外的消息类型）都可能导致 400。

### 原因五：function 定义中的 `strict` 参数

Spring AI 生成的 function schema 可能包含 `"strict": true` 参数。DeepSeek 不识别此参数，可能导致请求被拒。

## 解决方案

### 方案一：显式设置 `tool_choice`（配置层，推荐先尝试）

在 `application.yml` 中通过 Spring AI 的 options 机制传递 `tool_choice`：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
          maxTokens: 50000
```

由于 Spring AI 1.1.0 可能不支持通过配置文件设置 `tool_choice`，需要在代码层构建 `ToolCallingChatOptions` 时显式设置。

### 方案二：通过自定义 RequestInterceptor 注入缺失参数（中等改动）

在 `module-config` 中创建拦截器，在请求发出前补充 `tool_choice`：

```java
@Component
public class DeepSeekCompatibilityInterceptor implements RequestInterceptor {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public void apply(Request request) {
        if (request.getURL().contains("deepseek")) {
            // 拦截并修改请求体，添加 tool_choice
            // ...
        }
    }
}
```

但此方案需要修改序列化后的 JSON，实现较复杂。

### 方案三：关闭流式工具调用（推荐，改动最小）

DeepSeek 对非流式请求的工具调用支持更好。可以在工具调用轮次使用非流式 API：

修改 `DearAgent.initChatClient()` 和 `scheduleRound()`，针对 DeepSeek 使用非流式调用：

```java
// 通过配置标识当前模型是否需要禁用流式工具调用
private boolean forceNonStreamingToolCall = false; // 由外部配置

private Flux<ChatResponse> streamOrCall(ChatClient.ChatClientRequestSpec spec) {
    if (forceNonStreamingToolCall) {
        return Flux.just(spec.call().chatResponse());
    }
    return spec.stream().chatResponse();
}
```

### 方案四：单工具调用串行化（减少不兼容风险）

修改 `DearAgent.finishRound()` 和 `executeToolCalls()`，将多个 tool_calls 拆分为单条独立消息：

```java
// 每个 tool_call 创建独立的 assistant 消息
for (AssistantMessage.ToolCall tc : state.toolCalls) {
    AssistantMessage singleAssistant = AssistantMessage.builder()
        .toolCalls(List.of(tc)).build();
    messages.add(singleAssistant);
    // 逐个执行工具并添加响应
    ToolResponseMessage.ToolResponse response = executeSingleTool(tc);
    messages.add(ToolResponseMessage.builder()
        .responses(List.of(response)).build());
}
```

### 方案五：使用 DashScope 兼容模式（零代码改动）

如果 DeepSeek 通过 DashScope 兼容模式也能访问，可以避免直接对接 DeepSeek API：

```yaml
spring:
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/
      chat:
        options:
          model: deepseek-v3  # 或 deepseek-r1
```

## 建议实施顺序

| 优先级 | 方案 | 改动量 | 风险 | 说明 |
|--------|------|--------|------|------|
| 1 | 方案五：DashScope 兼容模式 | 零改动 | 低 | 如果 DashScope 提供 DeepSeek 模型，首选此方案 |
| 2 | 方案三：关闭流式工具调用 | 小 | 低 | 工具调用轮次改用非流式，用户体验影响小 |
| 3 | 方案四：单工具串行化 | 中 | 中 | 需要重构消息构建逻辑 |
| 4 | 方案二：自定义拦截器 | 大 | 高 | 需要深入理解 Spring AI 内部 API |

## 验证方法

1. 先用 **curl 直接调用 DeepSeek API**，确认带 tools + stream 的请求能否正常返回：

```bash
curl -X POST https://api.deepseek.com/v1/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "现在几点了？"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "获取当前时间",
        "parameters": {"type": "object", "properties": {}}
      }
    }],
    "tool_choice": "auto",
    "stream": false
  }'
```

2. 分别测试 `stream: true` 和 `stream: false`，确认 DeepSeek 流式工具调用是否可用

3. 对照 Spring AI 实际发出的请求体（开启 DEBUG 日志），与成功的手动 curl 请求进行对比

## 相关参考

- [DeepSeek API 文档 - Function Calling](https://api-docs.deepseek.com/guides/function_calling)
- [Spring AI OpenAI Chat Documentation](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- 堆栈路径：`OpenAiChatModel.internalStream → MessageAggregator.aggregate → 400 Bad Request`
