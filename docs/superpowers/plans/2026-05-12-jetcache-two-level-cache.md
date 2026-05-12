# JetCache Two-Level Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JetCache-based two-level caching (Caffeine local + Redisson remote) to session/web query interfaces to reduce MySQL load.

**Architecture:** JetCache annotations (`@Cached`, `@CacheInvalidate`) on service-layer query methods with Spring EL key expressions. Local Caffeine cache for sub-millisecond access; remote Redisson cache distributes invalidation across instances. CacheType.BOTH enables the two-level lookup (local → remote → DB).

**Tech Stack:** JetCache 2.7.5, Caffeine 3.1.8, Redisson 3.24.3 (already present), Spring Boot 3.5.6

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `pom.xml` (parent) | Modify | Add jetcache/caffeine version properties and dependencyManagement entries |
| `module-common/pom.xml` | Modify | Add jetcache-starter-redisson and caffeine dependencies |
| `app/src/main/resources/application.yml` | Modify | Add jetcache configuration block |
| `module-common/src/main/java/.../common/constant/CacheConstant.java` | Create | Cache key separator constant |
| `app/src/main/java/.../dear/config/CacheConfiguration.java` | Create | `@EnableMethodCache` configuration class |
| `module-session/.../service/impl/ChatConversationServiceImpl.java` | Modify | Add `@Cached` on `getBySessionId`, override `updateById`/`removeById` with invalidation |
| `module-session/.../service/ChatConversationService.java` | Modify | Add `evictBySessionId` method signature |
| `module-session/.../service/impl/ChatMessageServiceImpl.java` | Modify | Add `@Cached` on `findByConversationId`, add eviction in save methods |
| `module-session/.../service/ChatMessageService.java` | Modify | Add `evictByConversationId` method signature |
| `module-session/.../service/impl/ModelConfigServiceImpl.java` | Modify | Add `@Cached` on list query methods, inject CacheManager for eviction |
| `module-session/.../service/ModelConfigService.java` | Modify | Add `evictListCache` method signature |
| `module-prompt/.../service/impl/AiPromptServiceImpl.java` | Modify | Override `getById` with `@Cached`, add eviction method |
| `module-prompt/.../service/AiPromptService.java` | Modify | Add `evictById` method signature |
| `module-web/.../controller/SessionController.java` | Modify | Call eviction after edit/delete |
| `module-web/.../controller/ModelAdminController.java` | Modify | Call eviction after create/update/delete |
| `module-web/.../controller/PromptController.java` | Modify | Call eviction after create/update/delete |

---

### Task 1: Add JetCache and Caffeine dependencies

**Files:**
- Modify: `pom.xml` (parent)
- Modify: `module-common/pom.xml`

- [ ] **Step 1: Add version properties and dependencyManagement to parent POM**

In `pom.xml` `<properties>`, add:

```xml
<caffeine.version>3.1.8</caffeine.version>
<jetcache.version>2.7.5</jetcache.version>
```

In `pom.xml` `<dependencyManagement>`, add before the internal modules section:

```xml
<!-- Caffeine local cache -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>${caffeine.version}</version>
</dependency>

<!-- JetCache with Redisson integration -->
<dependency>
    <groupId>com.alicp.jetcache</groupId>
    <artifactId>jetcache-starter-redisson</artifactId>
    <version>${jetcache.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

- [ ] **Step 2: Add dependencies to module-common/pom.xml**

In `module-common/pom.xml`, add inside `<dependencies>` after the redisson dependency:

```xml
<dependency>
    <groupId>com.alicp.jetcache</groupId>
    <artifactId>jetcache-starter-redisson</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 3: Verify dependency resolution**

```bash
mvn dependency:resolve -pl module-common
```

Expected: BUILD SUCCESS, no missing artifacts.

---

### Task 2: Configure JetCache in application.yml

**Files:**
- Modify: `app/src/main/resources/application.yml`

- [ ] **Step 1: Add JetCache configuration block**

Append to `app/src/main/resources/application.yml`:

```yaml
jetcache:
  statIntervalMinutes: 0
  areaInCacheName: false
  local:
    default:
      type: caffeine
      keyConvertor: fastjson2
      limit: 100
  remote:
    default:
      type: redisson
      keyConvertor: fastjson2
      broadcastChannel: ${spring.application.name}
      keyPrefix: ${spring.application.name}
      valueEncoder: java
      valueDecoder: java
      defaultExpireInMillis: 5000
```

- [ ] **Step 2: Verify config parses correctly**

```bash
mvn spring-boot:run -pl app 2>&1 | grep -iE "jetcache|JetCache|JetCacheCache"
```

Expected: App starts without config parse errors. The JetCache auto-configuration log line should appear.

---

### Task 3: Create CacheConstant and CacheConfiguration

**Files:**
- Create: `module-common/src/main/java/com/roydon/dear/common/constant/CacheConstant.java`
- Create: `app/src/main/java/com/roydon/dear/config/CacheConfiguration.java`

- [ ] **Step 1: Create CacheConstant**

```java
package com.roydon.dear.common.constant;

public class CacheConstant {

    /**
     * 缓存key分隔符
     */
    public static final String CACHE_KEY_SEPARATOR = ":";

}
```

- [ ] **Step 2: Create CacheConfiguration**

```java
package com.roydon.dear.config;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableMethodCache(basePackages = "com.roydon.dear")
public class CacheConfiguration {
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS.

---

### Task 4: Cache ChatConversation queries and invalidate on writes

**Files:**
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/ChatConversationService.java`
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/impl/ChatConversationServiceImpl.java`

- [ ] **Step 1: Add eviction method to interface**

In `ChatConversationService.java`, add:

```java
void evictBySessionId(String sessionId);
```

- [ ] **Step 2: Add @Cached and eviction to implementation**

In `ChatConversationServiceImpl.java`, add imports:

```java
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;

import java.io.Serializable;
```

Replace `getBySessionId` and add `evictBySessionId`, `updateById` override, and `removeById` override:

```java
@Override
@Cached(name = "conversation:cache:", key = "#sessionId", cacheType = CacheType.BOTH, cacheNullValue = true)
public ChatConversation getBySessionId(String sessionId) {
    return lambdaQuery()
            .eq(ChatConversation::getSessionId, sessionId)
            .eq(ChatConversation::getDelFlag, "0")
            .one();
}

@Override
@CacheInvalidate(name = "conversation:cache:", key = "#sessionId")
public void evictBySessionId(String sessionId) {
    // 仅触发缓存失效
}

@Override
public boolean updateById(ChatConversation entity) {
    boolean result = super.updateById(entity);
    if (result && entity.getSessionId() != null) {
        evictBySessionId(entity.getSessionId());
    }
    return result;
}

@Override
public boolean removeById(Serializable id) {
    ChatConversation conv = getById(id);
    boolean result = super.removeById(id);
    if (result && conv != null && conv.getSessionId() != null) {
        evictBySessionId(conv.getSessionId());
    }
    return result;
}
```

---

### Task 5: Cache ChatMessage queries and invalidate on writes

**Files:**
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/ChatMessageService.java`
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/impl/ChatMessageServiceImpl.java`

- [ ] **Step 1: Add eviction method to interface**

In `ChatMessageService.java`, add:

```java
void evictByConversationId(Long conversationId);
```

- [ ] **Step 2: Add @Cached, eviction, and wire into save methods**

In `ChatMessageServiceImpl.java`, add imports:

```java
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
```

Replace `findByConversationId`:

```java
@Override
@Cached(name = "message:cache:", key = "#conversationId", cacheType = CacheType.BOTH, cacheNullValue = true)
public List<ChatMessage> findByConversationId(Long conversationId) {
    return lambdaQuery()
            .eq(ChatMessage::getConversationId, conversationId)
            .eq(ChatMessage::getDelFlag, "0")
            .orderByAsc(ChatMessage::getCreateTime)
            .list();
}
```

Add eviction method:

```java
@Override
@CacheInvalidate(name = "message:cache:", key = "#conversationId")
public void evictByConversationId(Long conversationId) {
    // 仅触发缓存失效
}
```

In `saveUserMessage`, add `evictByConversationId(conversationId);` after `save(msg);`.

In `saveAssistantMessage`, add `evictByConversationId(conversationId);` after `save(msg);`.

---

### Task 6: Cache ModelConfig list queries with CacheManager eviction

**Files:**
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/ModelConfigService.java`
- Modify: `module-session/src/main/java/com/roydon/dear/session/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: Add eviction method to interface**

In `ModelConfigService.java`, add:

```java
void evictListCache();
```

- [ ] **Step 2: Add @Cached annotations and CacheManager-based eviction**

In `ModelConfigServiceImpl.java`, add imports:

```java
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import jakarta.annotation.Resource;
```

Add field:

```java
@Resource
private CacheManager cacheManager;
```

Modify `listEnabled`:

```java
@Override
@Cached(name = "modelConfig:enabled", cacheType = CacheType.BOTH, localExpireInMillis = 300000)
public List<ModelConfig> listEnabled() {
    LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
            .eq(ModelConfig::getEnabled, true)
            .orderByAsc(ModelConfig::getSortOrder);
    return this.list(wrapper);
}
```

Modify `listByCategory`:

```java
@Override
@Cached(name = "modelConfig:category:", key = "#category", cacheType = CacheType.BOTH, localExpireInMillis = 300000)
public List<ModelConfig> listByCategory(String category) {
    LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
            .eq(ModelConfig::getCategory, category)
            .orderByAsc(ModelConfig::getSortOrder);
    return this.list(wrapper);
}
```

Modify `listAllOrdered`:

```java
@Override
@Cached(name = "modelConfig:list:", key = "(#category ?: 'all') + '_' + (#enabled ?: 'all')", cacheType = CacheType.BOTH, localExpireInMillis = 300000)
public List<ModelConfig> listAllOrdered(String category, Boolean enabled) {
    LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
            .eq(StringUtils.isNotBlank(category), ModelConfig::getCategory, category)
            .eq(Objects.nonNull(enabled), ModelConfig::getEnabled, enabled)
            .orderByAsc(ModelConfig::getSortOrder);
    return this.list(wrapper);
}
```

Add eviction method — injects CacheManager to clear multiple cache areas at once:

```java
@Override
public void evictListCache() {
    com.alicp.jetcache.Cache<String, List<ModelConfig>> c;
    c = cacheManager.getCache("modelConfig:enabled");
    if (c != null) c.removeAll();
    c = cacheManager.getCache("modelConfig:category:");
    if (c != null) c.removeAll();
    c = cacheManager.getCache("modelConfig:list:");
    if (c != null) c.removeAll();
}
```

---

### Task 7: Cache AiPrompt getById

**Files:**
- Modify: `module-prompt/src/main/java/com/roydon/dear/prompt/service/AiPromptService.java`
- Modify: `module-prompt/src/main/java/com/roydon/dear/prompt/service/impl/AiPromptServiceImpl.java`

- [ ] **Step 1: Add eviction method to interface**

In `AiPromptService.java`, add:

```java
void evictById(Long id);
```

- [ ] **Step 2: Override getById with @Cached, add eviction method**

In `AiPromptServiceImpl.java`, add imports:

```java
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;

import java.io.Serializable;
```

Add overrides:

```java
@Override
@Cached(name = "prompt:cache:", key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true)
public AiPrompt getById(Serializable id) {
    return super.getById(id);
}

@Override
@CacheInvalidate(name = "prompt:cache:", key = "#id")
public void evictById(Long id) {
    // 仅触发缓存失效
}
```

---

### Task 8: Wire cache eviction into controllers

**Files:**
- Modify: `module-web/src/main/java/com/roydon/dear/web/controller/SessionController.java`
- Modify: `module-web/src/main/java/com/roydon/dear/web/controller/ModelAdminController.java`
- Modify: `module-web/src/main/java/com/roydon/dear/web/controller/PromptController.java`

- [ ] **Step 1: SessionController — evict after edit and delete**

In `editSession` method, after `conversationService.updateById(conversation);` add:

```java
conversationService.evictBySessionId(conversationId);
```

In `deleteSession` method, after the two `lambdaUpdate()` blocks, add:

```java
conversationService.evictBySessionId(conversationId);
messageService.evictByConversationId(conversation.getId());
```

- [ ] **Step 2: ModelAdminController — evict after write ops**

In `create` method, after `configService.save(cfg);` add:

```java
configService.evictListCache();
```

In `update` method, after `configService.updateById(cfg);` add:

```java
configService.evictListCache();
```

In `delete` method, before `return BaseResult.newSuccess();` add:

```java
configService.evictListCache();
```

- [ ] **Step 3: PromptController — evict after write ops**

In `createPrompt` method, after `promptService.save(prompt);` add:

```java
promptService.evictById(prompt.getId());
```

In `updatePrompt` method, after `promptService.updateById(prompt);` add:

```java
promptService.evictById(id);
```

In `deletePrompt` method, before `return BaseResult.newSuccess();` add:

```java
promptService.evictById(id);
```

---

### Task 9: Build and verify

- [ ] **Step 1: Full project compile**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Start application**

```bash
mvn spring-boot:run -pl app
```

Expected: App starts without errors, JetCache auto-configures.

- [ ] **Step 3: Verify cache behavior**

Hit `GET /session/{conversationId}` twice — second call should return faster (no DB query for conversation/messages).

Hit `POST /model/config` then `GET /model/config` — should return fresh data (cache was evicted).

Hit `PUT /session/{conversationId}` then `GET /session/{conversationId}` — should return updated data.

---

## Cache Summary

| Cache Area | Key | Cached Method | Invalidated By |
|------------|-----|---------------|----------------|
| `conversation:cache:` | `sessionId` | `getBySessionId` | `evictBySessionId`, `updateById`, `removeById` |
| `message:cache:` | `conversationId` | `findByConversationId` | `evictByConversationId`, `saveUserMessage`, `saveAssistantMessage` |
| `modelConfig:enabled` | (none) | `listEnabled` | `evictListCache` (via CacheManager) |
| `modelConfig:category:` | `category` | `listByCategory` | `evictListCache` (via CacheManager) |
| `modelConfig:list:` | `category_enabled` | `listAllOrdered` | `evictListCache` (via CacheManager) |
| `prompt:cache:` | `id` | `getById` | `evictById` |
