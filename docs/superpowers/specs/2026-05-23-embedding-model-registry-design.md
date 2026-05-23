# EmbeddingModel Registry & Vectorization Pipeline Design

**Date**: 2026-05-23
**Status**: Approved

## Context

The project currently uses `ModelRegistry` to manage `ChatModel` instances (dynamic creation from DB config, caching, refresh). However, the knowledge module's vectorization pipeline (`EmbedProcess`) requires `EmbeddingModel`, which has no registry support. Additionally, the vector store configuration references a non-standard `ElasticsearchEmbeddingStore` class instead of Spring AI's `ElasticsearchVectorStore`.

## Changes

### 1. ModelRegistry — Add EmbeddingModel Support

**File**: `module-model/.../model/registry/ModelRegistry.java`

Add a second cache and accessor methods for `EmbeddingModel`, mirroring the existing `ChatModel` pattern:

- `embeddingModelCache`: `ConcurrentHashMap<String, EmbeddingModel>`
- `getEmbeddingModel(String name)` — lazy-create via `computeIfAbsent` → `createEmbeddingModel(name)`
- `getDefaultEmbeddingModel(String category)` — look up default name from `defaultModelNames`, then delegate to `getEmbeddingModel()`
- `createEmbeddingModel(String name)` — load `ModelConfig` from DB, find matching `ModelProvider`, call `provider.createEmbeddingModel(cfg)`
- Update `refresh()` and `destroy()` to manage both caches

All `ModelProvider` implementations already have `createEmbeddingModel()` (Dashscope, OpenAI, ZhiPu, Ollama, etc.), so no provider changes needed.

### 2. ElasticSearchConfiguration — Use Standard ElasticsearchVectorStore

**File**: `module-knowledge/.../rag/config/ElasticSearchConfiguration.java`

- Remove the non-standard `ElasticsearchEmbeddingStore` bean
- Create `ElasticsearchVectorStore` bean using Spring AI's builder:
  - `ElasticsearchVectorStore.builder(restClient, embeddingModel)`
  - Configure `indexName`, `dimensions`, `similarity` from `ElasticSearchProperties`
  - `initializeSchema(true)` for auto index creation
- Inject `EmbeddingModel` from `ModelRegistry`

### 3. EmbedProcess — Complete Vectorization Logic

**File**: `module-knowledge/.../process/EmbedProcess.java`

Rewrite `embedAndStore(KnowledgeFileDO fileDO)`:
1. Validate file status (`CHUNKED`, not already `VECTOR_STORED`)
2. Page through segments: status=CHUNKED, no embeddingId, skipEmbedding=0
3. Convert segments to `List<Document>` (Spring AI documents with metadata)
4. Call `vectorStore.add(documents)` — ElasticsearchVectorStore auto-embeds via its configured EmbeddingModel
5. Update each segment: set `embeddingId` (from ES response), status=`VECTOR_STORED`
6. Paginate until all segments processed
7. Double-check: count remaining unprocessed segments; if 0, update file status to `VECTOR_STORED`

Remove the broken `embed()` and duplicate `embedAndStore()` helper methods.

### 4. FileProcessComponent — Wire Step 4

**File**: `module-knowledge/.../process/FileProcessComponent.java`

Replace the `// todo 4、进行向量化` comment with an actual call to `embedProcess.embedAndStore(fileDO)`.

## Data Flow

```
FileProcessComponent.processFile(fileDO)
  ├─ 1. FileProcessStrategy.processFile()  → converted file
  ├─ 2. FileSplitter.split()                → List<Document>
  ├─ 3. Save segments to DB (status=CHUNKED) + update file status=CHUNKED
  └─ 4. EmbedProcess.embedAndStore(fileDO)
        ├─ Page segments (CHUNKED, no embeddingId)
        ├─ Convert to List<Document>
        ├─ vectorStore.add(documents)        → ES stores vectors
        ├─ Update segments: embeddingId + status=VECTOR_STORED
        └─ If all done: file status=VECTOR_STORED
```

## Files Modified

| File | Change |
|------|--------|
| `module-model/.../registry/ModelRegistry.java` | Add `EmbeddingModel` cache + accessors |
| `module-knowledge/.../config/ElasticSearchConfiguration.java` | Replace with `ElasticsearchVectorStore` bean |
| `module-knowledge/.../process/EmbedProcess.java` | Complete vectorization logic |
| `module-knowledge/.../process/FileProcessComponent.java` | Wire step 4: call `embedProcess.embedAndStore()` |

## Edge Cases

- **Embedding model not configured**: `getDefaultEmbeddingModel("embedding")` throws if no default — caller handles
- **Partial failure during vectorization**: segments that fail to get embeddingId remain CHUNKED; next invocation retries
- **Concurrent processing**: `@DistributeLock` on `FileProcessComponent.processFile` prevents duplicate processing
- **skipEmbedding segments**: filtered out in query, never sent to vector store
