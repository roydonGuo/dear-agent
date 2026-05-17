## 模块化 RAG 技术方案

### 整体架构

```
POST /agent/chat/stream?query=...&conversationId=...&knowledgeBaseIds=1,2,3
  │
  ▼
AgentController
  │
  ├─ 1. Query Rewriting（查询改写/富化）
  │     用户原始问题 → ChatModel → 多视角检索查询
  │
  ├─ 2. Knowledge Retrieval（Elasticsearch 8 混合检索）
  │     改写后的查询 → EmbeddingModel → 向量检索 + BM25 全文检索
  │     过滤条件: base_id IN (knowledgeBaseIds)
  │     Top-K 去重合并
  │
  ├─ 3. Context Injection（上下文注入）
  │     检索结果 → 格式化模板 → 注入 System Prompt
  │
  ├─ 4. Agent Processing（DearAgent 正常流程）
  │     LLM 基于注入的知识上下文回答问题
  │     + 可选的工具调用（搜索、文件操作等）
  │
  └─ 5. Card-style Reference Output（卡片化引用输出）
       SSE 事件中携带结构化的引用文档数据
```

### 一、Elasticsearch 8 向量存储

新增依赖 `spring-ai-elasticsearch-store` 到 `module-knowledge/pom.xml`：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-elasticsearch-store</artifactId>
    <version>${spring.ai.version}</version>
</dependency>
```

**ES 索引设计**（单索引 `knowledge_chunks`，按 `base_id` 过滤）：

```json
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      },
      "content":     { "type": "text", "analyzer": "ik_max_word" },
      "base_id":     { "type": "long" },
      "file_id":     { "type": "long" },
      "file_name":   { "type": "keyword" },
      "chunk_index": { "type": "integer" },
      "chunk_id":    { "type": "keyword" },
      "mime_type":   { "type": "keyword" },
      "create_time": { "type": "date" }
    }
  }
}
```

**检索策略：混合检索**（向量相似度 + BM25 关键词，取交集/加权合并）：

```
向量检索 (cosineSimilarity) ─┐
                              ├─ 加权融合 → Top-K 结果
BM25 全文检索 ───────────────┘
+ base_id 过滤
```

### 二、多格式文档解析

采用**策略模式**，按 `mineType` 路由：

```
DocumentParserRegistry
  ├─ MarkdownParser     (text/markdown, text/plain)
  │    输入: content 字段（已有）
  │    处理: 保留标题结构作为 chunk 元数据
  │
  ├─ PdfParser          (application/pdf)
  │    输入: MinIO storagePath → 下载 PDF 字节流
  │    解析: Apache PDFBox 提取文本
  │    回写: 解析后的文本内容更新到 content 字段
  │
  └─ (后续扩展)
       ImageParser      (image/*) → OCR / 多模态 Embedding
       TikaParser       (docx/xlsx/pptx) → Apache Tika 通用解析
```

**接口定义：**

```java
interface DocumentParser {
    String parse(KnowledgeFileDO file);  // 返回解析后的纯文本
    Set<String> supportedMimeTypes();
    default boolean supports(String mimeType) {
        return supportedMimeTypes().contains(mimeType);
    }
}
```

**PDF 解析关键点：**
- 从 MinIO 下载文件流 → `fileStorage.download(bucket, storagePath)`
- Apache PDFBox `PDFTextStripper` 提取文本
- 解析后的文本**回写到 `content` 字段**，避免重复解析
- 在文件上传后异步触发（或同步触发首次解析）

### 三、文档摄取流程 (Ingestion Pipeline)

触发时机：文件上传完成后（可异步）。

```
KnowledgeFileDO (content 已填充)
  │
  ▼
DocumentSplitter (TokenTextSplitter)
  chunkSize: 500 tokens, overlap: 50 tokens
  │
  ▼
List<Document> chunks (每个含 content + metadata)
  │
  ▼
EmbeddingModel.embed(chunks)  →  List<Vector>
  │
  ▼
ElasticsearchVectorStore.add(documents)
  │ 每条 Document 携带:
  │   id: "{fileId}_{chunkIndex}"
  │   embedding: float[1024]
  │   metadata: { base_id, file_id, file_name, chunk_index, mime_type }
  │   content: 原始文本块
```

**数据库新增表 `ai_knowledge_chunk`**（轻量元数据，向量本身在 ES）：

```sql
CREATE TABLE ai_knowledge_chunk (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id     BIGINT NOT NULL,
    base_id     BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_id    VARCHAR(64) NOT NULL COMMENT 'ES中的文档ID',
    content     LONGTEXT COMMENT '分块文本内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_base_file (base_id, file_id)
);
```

### 四、查询改写/富化 (Query Rewriting)

在检索之前，用一个轻量 LLM 调用将用户问题改写为更适合检索的形式：

```
用户问题: "知识库里关于微服务有什么内容？"
    ↓ QueryRewriter (ChatModel, 低 temperature)
改写结果: ["微服务架构设计原则", "微服务部署方案", "Spring Cloud 微服务实践"]
    ↓ 并行 Embedding
3 组向量 → ES 检索 → 合并去重 → 按分数排序 Top-K
```

**核心逻辑（伪代码）：**

```java
public List<String> rewriteQuery(String userQuery, String conversationContext) {
    String prompt = """
        你是一个查询改写助手。将用户问题改写为1-3个适合知识库检索的查询。
        每个查询应聚焦不同角度，使用更专业、更具体的关键词。
        输出纯JSON数组格式，不要有其他内容。
        
        对话上下文: %s
        用户问题: %s
        """.formatted(conversationContext, userQuery);
    
    String result = chatModel.call(prompt);
    return JSON.parseArray(result, String.class);
}
```

### 五、Agent 集成方式（非 @Tool）

不创建独立的 Tool，而是作为 **预处理步骤** 嵌入 Agent 流程：

```java
// AgentController 中的改造
public Flux<String> webSearchStream(
        @RequestParam String query,
        @RequestParam String conversationId,
        @RequestParam(required = false) List<Long> knowledgeBaseIds,  // 新增
        @RequestParam(required = false) Boolean think,
        @RequestParam(required = false) Boolean webSearch) {

    // 1. 如果指定了知识库，先检索
    String knowledgeContext = null;
    List<RetrievalResult> references = List.of();
    if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
        // 1a. 查询改写
        List<String> rewrittenQueries = queryRewriter.rewrite(query, conversationContext);
        // 1b. 混合检索
        references = retrievalService.search(rewrittenQueries, knowledgeBaseIds, topK=5);
        // 1c. 格式化上下文
        knowledgeContext = formatKnowledgeContext(references);
    }

    // 2. 将知识上下文注入 system prompt
    DearAgent dearAgent = initDearAgent(conversationId, webSearch, knowledgeContext);

    // 3. 正常流程（引用数据留给 finally 阶段输出）
    return dearAgent.stream(conversationId, query, think, references);
}
```

### 六、卡片化引用输出

在 AgentResponse 中新增引用数据结构，在 SSE `done` 之前输出：

```json
{
  "type": "reference",
  "data": [
    {
      "fileId": 123,
      "fileName": "微服务架构设计.pdf",
      "snippet": "微服务是一种将单一应用程序划分为一组小服务的架构风格...",
      "score": 0.92,
      "chunkIndex": 5
    },
    {
      "fileId": 456,
      "fileName": "Spring Cloud实践.md",
      "snippet": "Spring Cloud Alibaba 提供了完整的微服务解决方案...",
      "score": 0.87,
      "chunkIndex": 2
    }
  ]
}
```

前端收到 `type: "reference"` 后渲染为可点击的卡片，点击可跳转到 MinIO 预签名文件 URL。

### 七、模块内文件结构

```
module-knowledge/
  pom.xml  (新增: spring-ai-elasticsearch-store, pdfbox)
  src/main/java/com/roydon/dear/knowledge/
    rag/
      config/
        ElasticsearchVectorStoreConfig.java   # ES 客户端 & VectorStore Bean
      parser/
        DocumentParser.java                   # 接口
        MarkdownParser.java                   # text/markdown
        PdfParser.java                        # application/pdf (PDFBox)
        DocumentParserRegistry.java           # 策略工厂
      ingestion/
        DocumentIngestionService.java         # 分块+向量化+写入ES
        DocumentSplitter.java                 # TokenTextSplitter 封装
      retrieval/
        KnowledgeRetrievalService.java        # 混合检索入口
        QueryRewriter.java                    # LLM 查询改写
      model/
        RetrievalResult.java                  # 检索结果 (fileId, fileName, snippet, score)
        KnowledgeChunkDO.java                 # 分块元数据实体
```

### 八、依赖清单（`module-knowledge/pom.xml` 新增）

| 依赖 | 用途 |
|------|------|
| `spring-ai-elasticsearch-store` | ES VectorStore |
| `spring-boot-starter-data-elasticsearch` | ES 客户端自动配置 |
| `org.apache.pdfbox:pdfbox:3.0.x` | PDF 文本提取 |
| `org.apache.tika:tika-core`（后续）| 通用文档解析 |

### 九、配置项（`application.yml` 新增）

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
    username: elastic
    password: xxx

knowledge:
  rag:
    chunk-size: 500        # 分块大小 (tokens)
    chunk-overlap: 50      # 重叠大小 (tokens)
    top-k: 5               # 检索返回条数
    retrieval-strategy: hybrid  # hybrid / vector / keyword
```

---

## 实施步骤

| 序号 | 内容 | 涉及模块 |
|------|------|----------|
| 1 | ES 环境搭建 + `ElasticsearchVectorStore` Bean 配置 | module-knowledge |
| 2 | `DocumentParser` 接口 + MarkdownParser + PdfParser + Registry | module-knowledge |
| 3 | `DocumentIngestionService`（分块+向量化+写入ES） | module-knowledge |
| 4 | `QueryRewriter`（LLM 查询改写） | module-knowledge |
| 5 | `KnowledgeRetrievalService`（混合检索+过滤+去重） | module-knowledge |
| 6 | `AgentController` 改造（接收 knowledgeBaseIds、调用检索、注入上下文） | module-web |
| 7 | System Prompt 知识上下文模板 | module-common |
| 8 | 卡片化引用输出（AgentResponse + DearAgent finally 阶段） | module-common, module-agent |
| 9 | 文件上传后触发摄取流程 | module-knowledge |

---

这个方案是否符合你的预期？有几个需要你确认的点：

1. **ES 混合检索策略**：向量检索和 BM25 的权重比例你倾向多少？默认建议 0.7 向量 + 0.3 BM25。
2. **查询改写**：是用一个单独的轻量模型调用还是复用现有的 chatModel？建议复用，单次调用成本很低。
3. **PDF 解析时机**：上传时同步解析还是异步？同步简单但可能拖慢上传响应；异步体验好但需要状态管理。
4. **摄取触发**：文件上传后自动触发，还是需要手动触发索引？建议自动触发。
