# EmbeddingModel Registry & Vectorization Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend ModelRegistry with EmbeddingModel caching, replace custom ElasticsearchEmbeddingStore with standard ElasticsearchVectorStore, and complete the EmbedProcess vectorization pipeline.

**Architecture:** Mirror the existing ChatModel cache pattern in ModelRegistry for EmbeddingModel. Use Spring AI's standard `ElasticsearchVectorStore` which auto-embeds documents via its configured `EmbeddingModel`. Wire `FileProcessComponent` step 4 to call `EmbedProcess.embedAndStore()` asynchronously.

**Tech Stack:** Spring AI 1.1.3, ElasticsearchVectorStore, MyBatis-Plus, Redisson lock

---

### Task 1: Extend ModelRegistry with EmbeddingModel Support

**Files:**
- Modify: `module-model/src/main/java/com/roydon/dear/model/registry/ModelRegistry.java`

- [ ] **Step 1: Add EmbeddingModel cache and accessor methods**

Add to `ModelRegistry.java`:

```java
// New import
import org.springframework.ai.embedding.EmbeddingModel;

// New field (alongside existing chatModelCache)
private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
```

Add these methods after `getDefaultChatModel`:

```java
public EmbeddingModel getEmbeddingModel(String name) {
    return embeddingModelCache.computeIfAbsent(name, this::createEmbeddingModel);
}

public EmbeddingModel getDefaultEmbeddingModel(String category) {
    String name = defaultModelNames.get(category);
    if (name == null) {
        throw new IllegalStateException("没有找到 " + category + " 类型的默认模型");
    }
    return getEmbeddingModel(name);
}

private EmbeddingModel createEmbeddingModel(String name) {
    ModelConfig cfg = configService.getByName(name);
    if (cfg == null) {
        throw new IllegalArgumentException("模型配置不存在: " + name);
    }
    ModelProvider provider = providers.stream()
            .filter(p -> p.supports(cfg.getProvider()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("不支持的供应商: " + cfg.getProvider()));

    log.info("创建 EmbeddingModel 实例: {} (provider={}, model={})", name, cfg.getProvider(), cfg.getModel());
    return provider.createEmbeddingModel(cfg);
}
```

- [ ] **Step 2: Update refresh() to manage both caches**

Replace the `refresh()` method:

```java
public synchronized void refresh(Long configId) {
    ModelConfig cfg = configService.getById(configId);
    if (cfg == null) {
        throw new IllegalArgumentException("模型配置不存在: " + configId);
    }
    // Evict from both caches
    ChatModel oldChat = chatModelCache.remove(cfg.getName());
    if (oldChat instanceof DisposableBean bean) {
        try { bean.destroy(); } catch (Exception ignored) {}
    }
    EmbeddingModel oldEmbed = embeddingModelCache.remove(cfg.getName());
    if (oldEmbed instanceof DisposableBean bean) {
        try { bean.destroy(); } catch (Exception ignored) {}
    }
    if (Boolean.TRUE.equals(cfg.getEnabled())) {
        defaultModelNames.put(cfg.getCategory(), cfg.getName());
    } else {
        defaultModelNames.remove(cfg.getCategory(), cfg.getName());
    }
    log.info("模型配置已刷新: {}", cfg.getName());
}
```

- [ ] **Step 3: Update remove() to manage both caches**

Replace the `remove()` method:

```java
public void remove(String name) {
    ChatModel oldChat = chatModelCache.remove(name);
    if (oldChat instanceof DisposableBean bean) {
        try { bean.destroy(); } catch (Exception ignored) {}
    }
    EmbeddingModel oldEmbed = embeddingModelCache.remove(name);
    if (oldEmbed instanceof DisposableBean bean) {
        try { bean.destroy(); } catch (Exception ignored) {}
    }
    defaultModelNames.values().removeIf(name::equals);
}
```

- [ ] **Step 4: Update destroy() to clean both caches**

Replace the `destroy()` method:

```java
@PreDestroy
public void destroy() {
    chatModelCache.values().forEach(cm -> {
        if (cm instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
    });
    chatModelCache.clear();
    embeddingModelCache.values().forEach(em -> {
        if (em instanceof DisposableBean bean) {
            try { bean.destroy(); } catch (Exception ignored) {}
        }
    });
    embeddingModelCache.clear();
    defaultModelNames.clear();
}
```

- [ ] **Step 5: Build the module to verify compilation**

```bash
mvn compile -pl module-model -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add module-model/src/main/java/com/roydon/dear/model/registry/ModelRegistry.java
git commit -m "feat(model): add EmbeddingModel caching and accessors to ModelRegistry"
```

---

### Task 2: Rewrite ElasticSearchConfiguration to Use ElasticsearchVectorStore

**Files:**
- Modify: `module-knowledge/src/main/java/com/roydon/dear/knowledge/rag/config/ElasticSearchConfiguration.java`
- Modify: `module-knowledge/src/main/java/com/roydon/dear/knowledge/rag/config/ElasticSearchProperties.java`

- [ ] **Step 1: Add dimensions and similarity fields to ElasticSearchProperties**

Edit `ElasticSearchProperties.java`:

```java
package com.roydon.dear.knowledge.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = ElasticSearchProperties.PREFIX)
public class ElasticSearchProperties {
    public static final String PREFIX = "elasticsearch";

    private String host;
    private String apiKey;
    private int dimensions = 1024;
    private String similarity = "cosine";
    private String indexName = "knowledge-vector";
    private boolean initializeSchema = true;
}
```

- [ ] **Step 2: Rewrite ElasticSearchConfiguration with ElasticsearchVectorStore**

Replace the entire `ElasticSearchConfiguration.java`:

```java
package com.roydon.dear.knowledge.rag.config;

import com.roydon.dear.model.registry.ModelRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ElasticSearchProperties.class)
public class ElasticSearchConfiguration {

    @Autowired
    private ElasticSearchProperties properties;

    @Autowired
    private ModelRegistry modelRegistry;

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient() {
        return RestClient
                .builder(HttpHost.create(properties.getHost()))
                .build();
    }

    @Primary
    @ConditionalOnMissingBean
    @Bean
    public ElasticsearchVectorStore elasticsearchVectorStore(RestClient restClient) {
        EmbeddingModel embeddingModel = modelRegistry.getDefaultEmbeddingModel("embedding");
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(properties.getIndexName());
        options.setDimensions(properties.getDimensions());
        if ("cosine".equals(properties.getSimilarity())) {
            options.setSimilarity(ElasticsearchVectorStoreOptions.SimilarityFunction.COSINE);
        } else if ("dot_product".equals(properties.getSimilarity())) {
            options.setSimilarity(ElasticsearchVectorStoreOptions.SimilarityFunction.DOT_PRODUCT);
        } else if ("l2_norm".equals(properties.getSimilarity())) {
            options.setSimilarity(ElasticsearchVectorStoreOptions.SimilarityFunction.L2_NORM);
        }
        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(properties.isInitializeSchema())
                .build();
    }
}
```

- [ ] **Step 3: Verify build compiles**

```bash
mvn compile -pl module-knowledge -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add module-knowledge/src/main/java/com/roydon/dear/knowledge/rag/config/ElasticSearchConfiguration.java module-knowledge/src/main/java/com/roydon/dear/knowledge/rag/config/ElasticSearchProperties.java
git commit -m "feat(knowledge): replace custom ElasticsearchEmbeddingStore with standard ElasticsearchVectorStore"
```

---

### Task 3: Complete EmbedProcess Vectorization Logic

**Files:**
- Modify: `module-knowledge/src/main/java/com/roydon/dear/knowledge/process/EmbedProcess.java`

- [ ] **Step 1: Rewrite EmbedProcess with complete embedAndStore logic**

Replace the entire `EmbedProcess.java`:

```java
package com.roydon.dear.knowledge.process;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileDO;
import com.roydon.dear.knowledge.domain.entity.KnowledgeFileSegmentDO;
import com.roydon.dear.knowledge.enums.FileSegmentStatus;
import com.roydon.dear.knowledge.enums.KnowledgeFileStatus;
import com.roydon.dear.knowledge.service.IKnowledgeFileSegmentService;
import com.roydon.dear.knowledge.service.IKnowledgeFileService;
import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbedProcess {

    private final IKnowledgeFileSegmentService knowledgeSegmentService;
    private final IKnowledgeFileService knowledgeFileService;
    private final ModelRegistry modelRegistry;
    private final VectorStore vectorStore;

    private static final int EMBEDDING_BATCH_SIZE = 100;

    public boolean embedAndStore(KnowledgeFileDO fileDO) {
        if (fileDO.getStatus() == KnowledgeFileStatus.VECTOR_STORED) {
            return true;
        }
        if (fileDO.getStatus() != KnowledgeFileStatus.CHUNKED) {
            log.warn("文件 {} 状态不是 CHUNKED，当前状态: {}", fileDO.getId(), fileDO.getStatus());
            return false;
        }

        LambdaQueryWrapper<KnowledgeFileSegmentDO> queryWrapper = Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                .eq(KnowledgeFileSegmentDO::getFileId, fileDO.getId())
                .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                .isNull(KnowledgeFileSegmentDO::getEmbeddingId)
                .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0);

        int pageNum = 1;
        Page<KnowledgeFileSegmentDO> page = knowledgeSegmentService.page(new Page<>(pageNum, EMBEDDING_BATCH_SIZE), queryWrapper);

        while (page.getCurrent() == 1 || page.hasNext()) {
            List<KnowledgeFileSegmentDO> segmentList = page.getRecords();
            if (segmentList.isEmpty()) {
                break;
            }

            List<Document> documents = segmentList.stream()
                    .map(segment -> {
                        Map<String, Object> metadata = JSON.parseObject(
                                segment.getMetadata(),
                                new TypeReference<Map<String, Object>>() {});
                        return new Document(segment.getText(), metadata);
                    })
                    .toList();

            try {
                vectorStore.add(documents);
                log.info("向量化并存储 {} 个片段", documents.size());
            } catch (Exception e) {
                log.error("向量存储失败: {}", e.getMessage(), e);
                return false;
            }

            for (KnowledgeFileSegmentDO segment : segmentList) {
                segment.setStatus(FileSegmentStatus.VECTOR_STORED);
                knowledgeSegmentService.updateById(segment);
            }

            pageNum++;
            page = knowledgeSegmentService.page(new Page<>(pageNum, EMBEDDING_BATCH_SIZE), queryWrapper);
        }

        // Double check: any remaining unprocessed segments?
        long remainingCount = knowledgeSegmentService.count(Wrappers.<KnowledgeFileSegmentDO>lambdaQuery()
                .eq(KnowledgeFileSegmentDO::getFileId, fileDO.getId())
                .eq(KnowledgeFileSegmentDO::getStatus, FileSegmentStatus.CHUNKED)
                .isNull(KnowledgeFileSegmentDO::getEmbeddingId)
                .eq(KnowledgeFileSegmentDO::getSkipEmbedding, 0));

        if (remainingCount == 0) {
            fileDO.setStatus(KnowledgeFileStatus.VECTOR_STORED);
            knowledgeFileService.updateById(fileDO);
            log.info("文件 {} 向量化完成", fileDO.getId());
            return true;
        }

        log.warn("向量存储未完全完成，剩余 {} 个片段未处理", remainingCount);
        return false;
    }
}
```

- [ ] **Step 2: Verify build compiles**

```bash
mvn compile -pl module-knowledge -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add module-knowledge/src/main/java/com/roydon/dear/knowledge/process/EmbedProcess.java
git commit -m "feat(knowledge): complete EmbedProcess vectorization logic with VectorStore"
```

---

### Task 4: Wire FileProcessComponent Step 4

**Files:**
- Modify: `module-knowledge/src/main/java/com/roydon/dear/knowledge/process/FileProcessComponent.java`

- [ ] **Step 1: Inject EmbedProcess and replace TODO with call**

Add field:

```java
private final EmbedProcess embedProcess;
```

Replace the `// todo 4、进行向量化` line (around line 107):

```java
// 4、进行向量化
embedProcess.embedAndStore(processedFileDO);
```

- [ ] **Step 2: Verify build compiles**

```bash
mvn compile -pl module-knowledge -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add module-knowledge/src/main/java/com/roydon/dear/knowledge/process/FileProcessComponent.java
git commit -m "feat(knowledge): wire EmbedProcess into FileProcessComponent step 4"
```

---

### Task 5: Full Build Verification

- [ ] **Step 1: Build entire project**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Run knowledge module tests (if any exist)**

```bash
mvn test -pl module-knowledge -q
```

Expected: Tests pass or none exist

- [ ] **Step 3: Verify git status is clean**

```bash
git status
```
