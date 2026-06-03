# RAG 知识库检索 — 技术文档

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                     DearAgent.streamInternal                      │
│                                                                   │
│  if (useKnowledgeBase) {                                         │
│    kbIds = parseKnowledgeBaseIds("1,2,3")                       │
│    docs = knowledgeRetrievalService.retrieve(query, kbIds, 5)   │
│    systemPrompt += formatAsContext(docs)                         │
│  }                                                                │
│                                                                   │
│  ▼ 注入 LLM 上下文  ▼                                             │
│  messages.add(new SystemMessage(knowledgeCtx))                   │
│  messages.add(new UserMessage("<question>" + query + "</question>")) │
└─────────────────────────────────────────────────────────────────┘
```

## Pipeline 设计模式

采用**管道模式（Pipeline / Chain of Responsibility）**，与文件处理管道 `FileConvertHandler → FileSplitHandler → SegmentSaveHandler → EmbeddingHandler` 风格一致。

```
┌──────────────┐   ┌──────────────────┐   ┌──────────────┐   ┌──────────────────────┐
│ QueryAnalysis│──▶│ HybridRetrieval  │──▶│ RrfReranking │──▶│ ParentChunkEnrichment│
│ Stage        │   │ Stage            │   │ Stage        │   │ Stage                │
├──────────────┤   ├──────────────────┤   ├──────────────┤   ├──────────────────────┤
│ kbIds→fileIds│   │ semantic (并行)  │   │ RRF 公式合并 │   │ parentChunkId→父分段 │
│ →filterExpr  │   │ keyword  (并行)  │   │ 去重 → TopK  │   │ 完整文本替换子chunk   │
└──────────────┘   └──────────────────┘   └──────────────┘   └──────────────────────┘
```

### 核心类

| 类 | 职责 |
|---|------|
| `RagContext` | 管道上下文，携带 query / kbIds / 中间结果 / 最终结果 |
| `RagStage` | 阶段接口，`void execute(RagContext)` |
| `RagPipeline` | 编排器，按顺序链式调用四个阶段 |
| `ParentChunkEnrichmentStage` | 父分段补全：子 chunk → 查 `ai_knowledge_file_segment` 取父分段全文 |
| `KnowledgeRetrievalService` | 对外入口，创建上下文、执行管道、格式化结果 |

## 阶段一：QueryAnalysisStage（查询分析）

**目的**：将前端传入的知识库 ID 解析为 ES 过滤条件。

当前 ES 文档 `metadata` 中只存储了 `fileId`，没有直接存 `baseId`（知识库 ID）。因此需要通过 MySQL 做一次映射：

```
knowledgeBaseIds [1, 2] 
  → SELECT id FROM ai_knowledge_file WHERE base_id IN (1, 2)
  → fileIds [101, 102, 103]
  → filterExpression: "fileId in ['101', '102', '103']"
```

- `knowledgeBaseIds` 为空 → 不设过滤，检索全部文档
- 查询不到文件 → filterExpression 为空，后续阶段跳过过滤

## 阶段二：HybridRetrievalStage（混合检索）

并行执行语义检索和关键词检索：

### 语义检索（向量相似度）

```java
vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(query)
        .topK(topK)
        .similarityThreshold(0.3)
        .filterExpression(filterExpression)  // 可选，按 fileId 过滤
        .build()
);
```

- 将 query 向量化后与 ES 中 `embedding` 字段做 cosine 相似度计算
- `similarityThreshold(0.3)` 过滤低相关度结果
- `filterExpression` 由阶段一生成，限定检索范围

### 关键词检索（BM25）

通过 `RestClient` 直接向 ES 发送 `match` 查询：

```json
{
  "size": 5,
  "query": {
    "bool": {
      "must": [
        { "match": { "content": "<用户查询>" } }
      ],
      "filter": [
        { "terms": { "metadata.fileId": [101, 102] } }
      ]
    }
  }
}
```

- ES 默认使用 BM25 算法对 `content` 字段打分
- `filter` 子句限定文件范围，不影响打分
- 返回 `_source.content` 和 `_source.metadata`，重建 `Document` 对象

### 并行执行

使用 `CompletableFuture` + 固定 2 线程池并行发起两次检索，超时 30 秒，任一失败不影响对方。

## 阶段三：RrfRerankingStage（RRF 重排序）

### 算法

**Reciprocal Rank Fusion**：将多路召回结果按排名而非绝对分数融合，避免不同打分函数（余弦相似度 vs BM25）不可比的问题。

```
RRF_score(d) = Σ 1 / (k + rank_i(d))

k = 60（平滑常数，降低极端排名的影响）
```

### 处理流程

1. 遍历语义检索结果，rank 从 1 开始，累加 `1/(60+rank)` 到该文档的 RRF 总分
2. 遍历关键词检索结果，同样累加
3. 同一文档在两路中均出现时，RRF 分数相加（提升）
4. 按 RRF 总分降序排序，取 TopK
5. 文档去重键：优先使用 ES `_id`，其次 `metadata.chunkId`，最后用 `text.hashCode()`
6. 最终分数写入 `Document.score`（通过 `mutate().score().build()`）

### 去重策略

```
id 去重优先级：
  ES _id（向量检索有，关键词检索有）
  → metadata.chunkId（分段 ID，跨检索方式一致）
  → text.hashCode()（兜底）
```

## 阶段四：ParentChunkEnrichmentStage（父分段补全）

**目的**：子 chunk 用于检索（粒度细、匹配准），父分段用于 LLM 上下文（粒度粗、语义完整）。

### 父子分段关系

文档在超大内容时会被 `MarkdownHeaderParentTextSplitter` 二次切割：

```
父分段 (chunkId=X, skipEmbedding=1)  ← 完整语义，不做向量化
  ├─ 子 chunk1 (parentChunkId=X)     ← 嵌入到 ES，可被检索
  ├─ 子 chunk2 (parentChunkId=X)     ← 嵌入到 ES，可被检索
  └─ 子 chunk3 (parentChunkId=X)     ← 嵌入到 ES，可被检索
```

### 处理流程

1. 遍历 RRF 后的合并结果，收集 `metadata.parentChunkId`
2. 去重后批量查询 `ai_knowledge_file_segment`：`SELECT chunk_id, text WHERE chunk_id IN (...)`
3. 将父分段完整文本写入 `metadata.parentChunkText`
4. `formatAsContext()` 优先使用 `parentChunkText`，并合并同一父分段下的多个子 chunk 为一条

### 优化效果

- 检索阶段：子 chunk 短小精准，向量匹配度高
- 上下文阶段：父分段完整，LLM 获得全部语义而非碎片信息
- 同一父分段的多个子 chunk 被合并为一条，避免重复上下文

## 检索结果注入

`KnowledgeRetrievalService.formatAsContext()` 将检索到的 `List<Document>` 格式化为 Markdown 文本，**优先使用父分段完整文本，同一父分段去重**：

```markdown
# 知识库检索结果：
## 片段 1
<文档内容>

## 片段 2
<文档内容>
```

作为一条独立的 `SystemMessage` 插入到对话历史之后、用户问题之前，供 LLM 参考。

## 数据关联全景

```
KnowledgeBaseDO (ai_knowledge_base)
  │ id
  │
  └── KnowledgeFileDO (ai_knowledge_file)
        │ id
        │ base_id  ──→ 通过 MyBatis-Plus 查询，base_id IN (kbIds)
        │
        └── KnowledgeFileSegmentDO (ai_knowledge_file_segment)
              │ metadata JSON: {fileId, baseId, fileName, path, chunkId, ...}
              │ text
              │
              └── ES Document (knowledge-vector)
                    │ id
                    │ content   ← text
                    │ embedding ← 向量化后的 text
                    │ metadata  ← {baseId, fileId, fileName, path, chunkId, ...}
```

**过滤链路**：`knowledgeBaseIds` → `KnowledgeFileDO.baseId` → `KnowledgeFileDO.id` → ES `metadata.fileId`

## ES 文档结构

```json
{
  "id": "abc123",
  "content": "这是从文件中提取的文本片段...",
  "embedding": [0.01, -0.03, 0.15, ...],  // 1536 维，cosine 相似度
  "metadata": {
    "baseId": 1,
    "fileId": 2056914581698805762,
    "fileName": "技术文档.md",
    "path": "uploads/tech-doc.md",
    "chunkId": "1234567890123456789",
    "headerLevel": 1,
    "title": "第一章 概述"
  }
}
```

## 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 设计模式 | Pipeline | 与现有 `FileProcessComponent` 链风格一致，易扩展新阶段 |
| 关键词检索方式 | ES match 查询 | 复用现有 `RestClient`，ES 内置 BM25 无需额外集成 |
| 融合算法 | RRF | 无需模型，适合不同打分函数的融合；k=60 是学术共识 |
| k 值 | 60 | 平滑常数，降低排名极端值的影响，工业界主流 |
| 混合检索并行 | `CompletableFuture` | 减少总延迟，语义+关键词约等于单次较慢的耗时 |
| 过滤实现 | 语义用 filterExpression，关键词用 ES terms filter | 语义走 Spring AI 封装，关键词直接构造 ES DSL |
| 父子分段分离 | 检索用子 chunk，上下文用父分段 | 子 chunk 短小匹配准，父分段完整语义全；`formatAsContext` 去重合并 |

## 流程图

```
用户请求 (useKnowledgeBase=true, knowledgeBaseIds="1,2")
│
├─ parseKnowledgeBaseIds("1,2") → [1, 2]
│
├─ QueryAnalysisStage
│   └─ SELECT id FROM ai_knowledge_file WHERE base_id IN (1, 2)
│   └─ → filterExpression: "fileId in ['101','102']"
│   └─ → resolvedFileIds: [101, 102]
│
├─ HybridRetrievalStage (并行)
│   ├─ 语义: VectorStore.similaritySearch(filterExpression, topK=5)
│   │   └─ 结果: [docA(0.92), docC(0.85), docE(0.71)]
│   │
│   └─ 关键词: RestClient match query + terms filter
│       └─ 结果: [docB(5.3), docA(3.1), docD(2.8)]
│
├─ RrfRerankingStage
│   ├─ docA: 1/(60+1) + 1/(60+2) = 0.0164 + 0.0161 = 0.0325
│   ├─ docB: 0/(...) + 1/(60+1) = 0.0164
│   ├─ docC: 1/(60+2) = 0.0161
│   ├─ docD: 1/(60+3) = 0.0159
│   ├─ docE: 1/(60+3) = 0.0159
│   └─ 排序: [docA, docB, docC, docD, docE]
│
├─ ParentChunkEnrichmentStage
│   ├─ 收集 parentChunkId: ["X", "Y"]
│   ├─ SELECT chunk_id, text FROM ai_knowledge_file_segment WHERE chunk_id IN ('X', 'Y')
│   └─ metadata.parentChunkText = 父分段完整文本
│
└─ formatAsContext()
    ├─ 优先使用 parentChunkText（父分段完整语义）
    ├─ 同一父分段下的多个子 chunk 合并为一条
    └─ → SystemMessage 注入 LLM 对话上下文
```
