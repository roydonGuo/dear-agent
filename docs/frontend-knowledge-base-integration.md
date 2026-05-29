# 知识库检索功能 - 前端对接文档

## 概述

对话接口 `GET /agent/chat/stream` 已集成知识库检索（RAG）能力。开启后，系统在回复前先从知识库检索相关内容作为 LLM 上下文，并将检索来源通过 `knowledge` 事件推送给前端。

## API 变更

### 接口

```
GET /agent/chat/stream
```

### 新增参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `useKnowledgeBase` | Boolean | 否 | `false` | 是否启用知识库检索 |
| `knowledgeBaseIds` | String | 否 | — | 指定知识库 ID，多个以英文逗号分隔 |

### 参数组合规则

| useKnowledgeBase | knowledgeBaseIds | 行为 |
|------------------|-------------------|------|
| `false` / 不传 | — | 不触发检索，正常对话 |
| `true` | 不传 / 空 | 检索所有知识库 |
| `true` | `"1,2,3"` | 仅检索 ID 为 1、2、3 的知识库 |

## 新增 SSE 事件类型: `knowledge`

开启知识库检索后，在首条 `text` 事件之前，会先推送一个 `type: "knowledge"` 的事件。

### 事件格式

```json
{
  "type": "knowledge",
  "content": [
    {
      "score": 0.085,
      "metadata": {
        "fileId": 123456789,
        "fileName": "技术文档.md",
        "path": "uploads/tech-doc.md",
        "chunkId": "987654321",
        "title": "第一章 概述"
      }
    }
  ],
  "count": 5
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定值 `"knowledge"` |
| `content` | Array | 检索到的知识片段元信息列表，按相关度降序 |
| `content[].score` | Number | RRF 重排序后的分数 |
| `content[].metadata` | Object | 来源信息：fileName、fileId、chunkId 等 |
| `count` | Number | 返回的片段总数 |

> **注意**：content 数组中不包含片段文本内容，仅返回 score 和 metadata。前端可依据 metadata.fileName 展示引用来源标签。

## SSE 事件时序

```
knowledge  →  text  →  text  →  ...  →  reference  →  recommend  →  done
```

## 前端对接示例

```javascript
// 请求
const params = {
  query: userInput,
  conversationId: sessionId,
  useKnowledgeBase: true,
  knowledgeBaseIds: selectedKbIds.join(',')  // "1,2,3"
};

// SSE 解析
case 'knowledge':
  const sources = JSON.parse(event.content);
  showKnowledgeSources(sources);  // 展示引用来源标签
  break;
```

## 注意事项

- 首次响应可能比普通对话稍慢 1-3 秒（检索耗时）
