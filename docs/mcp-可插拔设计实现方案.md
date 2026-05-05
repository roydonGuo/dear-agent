# MCP 可插拔动态管理系统实现方案

## 1. 目标

- 支持 **HTTP SSE** 和 **stdio** 两种 MCP 传输方式
- 通过**前端管理界面**动态添加/编辑/删除/启停 MCP 服务
- 运行时**热加载**，无需重启应用
- 当前硬编码的 Tavily 平滑迁移到新体系

---

## 2. 数据库设计

```sql
CREATE TABLE `mcp_server_config` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(100) NOT NULL COMMENT '唯一标识，如 tavily-web-search',
    `label`          VARCHAR(100) NOT NULL COMMENT '展示名称，如 Tavily 网页搜索',
    `transport`      VARCHAR(10)  NOT NULL COMMENT '传输方式: http / stdio',
    `mcp_url`        VARCHAR(500) NULL       COMMENT 'HTTP 模式必填: MCP 服务端点',
    `api_key`        VARCHAR(500) NULL       COMMENT 'HTTP 模式可选: Bearer Token',
    `command`        VARCHAR(500) NULL       COMMENT 'Stdio 模式必填: 启动命令',
    `args`           TEXT         NULL       COMMENT 'Stdio 模式可选: 命令参数 (JSON 数组)',
    `env`            TEXT         NULL       COMMENT 'Stdio 模式可选: 环境变量 (JSON 对象)',
    `description`    VARCHAR(500) NULL       COMMENT '描述信息',
    `enabled`        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `sort_order`     INT          NOT NULL DEFAULT 0  COMMENT '排序，越小越前',
    `timeout_sec`    INT          NOT NULL DEFAULT 300 COMMENT '请求超时秒数',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

初始种子数据（迁移当前 Tavily 配置）:

```sql
INSERT INTO `mcp_server_config` (`name`, `label`, `transport`, `mcp_url`, `api_key`, `enabled`, `sort_order`)
VALUES ('tavily-web-search', 'Tavily 网页搜索', 'http',
        'https://mcp.tavily.com/mcp/',
        'tvly-dev-xxxx', 1, 1);
```

---

## 3. 模块结构变更

### 3.1 module-session：新增实体 + Mapper + Service

```
module-session/src/main/java/com/roydon/dear/session/
├── entity/
│   ├── AiSession.java          (已有)
│   └── McpServerConfig.java    (新增)
├── mapper/
│   ├── AiSessionMapper.java    (已有)
│   └── McpServerConfigMapper.java  (新增)
├── service/
│   ├── AiSessionService.java   (已有)
│   ├── McpServerConfigService.java (新增)
│   └── impl/
│       ├── AiSessionServiceImpl.java
│       └── McpServerConfigServiceImpl.java (新增)
```

### 3.2 module-tool：新增 McpRegistry + 重构 McpToolManager

```
module-tool/src/main/java/com/roydon/dear/tool/
├── FileOperationTools.java  (已有)
├── WeatherService.java      (已有)
├── McpToolManager.java      (重写，去掉 InitializingBean)
└── registry/
    ├── McpRegistry.java        (新增：MCP 连接管理器)
    ├── McpClientHolder.java    (新增：McpSyncClient + ToolCallback[] 包装)
    └── transport/
        ├── McpTransportFactory.java  (新增：工厂，根据 transport 创建)
        └── StdioMcpConnector.java    (新增：stdio 连接器)
```

### 3.3 module-web：新增 AdminController

```
module-web/src/main/java/com/roydon/dear/web/controller/
├── AgentController.java      (已有)
├── SessionController.java    (已有)
├── VoiceAgentController.java (已有)
├── AuthController.java       (已有)
└── admin/
    └── McpAdminController.java  (新增：MCP 管理 API)
```

---

## 4. 核心类实现

### 4.1 McpServerConfig 实体

```java
@Data
@TableName("mcp_server_config")
public class McpServerConfig {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String label;
    /** http / stdio */
    private String transport;
    private String mcpUrl;
    private String apiKey;
    private String command;
    private String args;        // JSON array string: ["--port","8080"]
    private String env;         // JSON object string: {"KEY":"VAL"}
    private String description;
    private Boolean enabled;
    private Integer sortOrder;
    private Integer timeoutSec;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 4.2 McpClientHolder（核心包装类）

```java
@Getter
public class McpClientHolder implements AutoCloseable {
    private final McpServerConfig config;
    private final McpSyncClient client;
    private final ToolCallback[] toolCallbacks;
    private final long createdAt;

    public McpClientHolder(McpServerConfig config, McpSyncClient client) {
        this.config = config;
        this.client = client;
        this.toolCallbacks = new SyncMcpToolCallbackProvider(
            SyncMcpToolCallbackProvider.builder().mcpClients(List.of(client)).build()
        ).getToolCallbacks();
        this.createdAt = System.currentTimeMillis();
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception e) {
            log.warn("关闭 MCP 客户端失败: {}", config.getName(), e);
        }
    }
}
```

### 4.3 McpRegistry（核心管理器）

```java
@Slf4j
@Component
public class McpRegistry implements InitializingBean, DisposableBean {

    /** name -> ClientHolder */
    private final Map<String, McpClientHolder> holders = new ConcurrentHashMap<>();
    @Autowired private McpServerConfigService configService;
    @Autowired private McpTransportFactory transportFactory;

    @Override
    public void afterPropertiesSet() {
        // 启动时只加载 enabled 的配置，但不初始化连接（懒加载）
        log.info("McpRegistry 初始化完成，已发现 {} 条 MCP 配置", configService.count());
    }

    /**
     * 获取指定名称的 MCP 客户端（懒加载）
     */
    public McpClientHolder getOrCreate(String name) {
        return holders.computeIfAbsent(name, this::initHolder);
    }

    /**
     * 刷新指定 MCP 配置（前端修改后调用）
     */
    public synchronized McpClientHolder refresh(Long configId) {
        McpServerConfig cfg = configService.getById(configId);
        if (cfg == null) throw new IllegalArgumentException("配置不存在: " + configId);
        close(cfg.getName());
        holders.remove(cfg.getName());
        if (cfg.getEnabled()) {
            return getOrCreate(cfg.getName());
        }
        return null;
    }

    /**
     * 触发所有已启用配置重新加载
     */
    public synchronized void refreshAll() {
        holders.values().forEach(h -> close(h.getConfig().getName()));
        holders.clear();
    }

    /**
     * 获取当前所有已启用工具的 ToolCallback（按 sort_order 排序）
     */
    public List<ToolCallback> getAllToolCallbacks() {
        return configService.listEnabledOrdered().stream()
            .filter(McpServerConfig::getEnabled)
            .flatMap(cfg -> {
                try {
                    McpClientHolder holder = getOrCreate(cfg.getName());
                    return Arrays.stream(holder.getToolCallbacks());
                } catch (Exception e) {
                    log.error("加载 MCP 工具失败: {}, 已跳过", cfg.getName(), e);
                    return Stream.empty();
                }
            })
            .collect(Collectors.toList());
    }

    // ===== 私有方法 =====

    private McpClientHolder initHolder(String name) {
        McpServerConfig cfg = configService.getByName(name);
        if (cfg == null) throw new IllegalArgumentException("MCP 配置不存在: " + name);
        log.info("初始化 MCP 客户端: {} ({})", name, cfg.getTransport());
        try {
            McpSyncClient client = transportFactory.create(cfg);
            client.initialize();
            log.info("MCP 客户端初始化成功: {}, 工具数: {}", name,
                client.listTools().size());
            return new McpClientHolder(cfg, client);
        } catch (Exception e) {
            throw new RuntimeException("MCP 客户端初始化失败: " + name, e);
        }
    }

    private void close(String name) {
        McpClientHolder h = holders.get(name);
        if (h != null) h.close();
    }

    @Override
    public void destroy() {
        holders.values().forEach(McpClientHolder::close);
        holders.clear();
    }
}
```

### 4.4 McpTransportFactory（传输工厂）

```java
@Component
@Slf4j
public class McpTransportFactory {

    /**
     * 根据配置创建 MCP 客户端
     */
    public McpSyncClient create(McpServerConfig cfg) {
        return switch (cfg.getTransport()) {
            case "http" -> createHttpClient(cfg);
            case "stdio" -> createStdioClient(cfg);
            default -> throw new IllegalArgumentException("不支持的传输类型: " + cfg.getTransport());
        };
    }

    private McpSyncClient createHttpClient(McpServerConfig cfg) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        if (StringUtils.isNotBlank(cfg.getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + cfg.getApiKey());
        }

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(cfg.getMcpUrl())
                .requestBuilder(requestBuilder)
                .build();

        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 300))
                .build();
    }

    private McpSyncClient createStdioClient(McpServerConfig cfg) {
        // 解析命令参数
        List<String> commandParts = new ArrayList<>();
        commandParts.add(cfg.getCommand());
        if (StringUtils.isNotBlank(cfg.getArgs())) {
            JSONArray argsArray = JSON.parseArray(cfg.getArgs());
            for (int i = 0; i < argsArray.size(); i++) {
                commandParts.add(argsArray.getString(i));
            }
        }

        // 解析环境变量
        Map<String, String> envMap = new HashMap<>();
        if (StringUtils.isNotBlank(cfg.getEnv())) {
            JSONObject envJson = JSON.parseObject(cfg.getEnv());
            for (String key : envJson.keySet()) {
                envMap.put(key, envJson.getString(key));
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.environment().putAll(envMap);

        return McpClient.sync(new ProcessBuilderStreamableTransport(processBuilder))
                .requestTimeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 300))
                .build();
    }
}
```

### 4.5 改造后的 McpToolManager（去掉 InitializingBean）

```java
@Component
@Slf4j
public class McpToolManager {

    @Autowired private McpRegistry registry;
    @Autowired private FileOperationTools fileOperationTools;
    @Autowired private WeatherService weatherService;

    /** 文件操作工具（始终可用） */
    private ToolCallback[] fileToolCallbacks;
    /** 天气预报工具（始终可用） */
    private ToolCallback[] weatherToolCallbacks;

    @PostConstruct
    public void init() {
        MethodToolCallbackProvider fileProvider = MethodToolCallbackProvider.builder()
                .toolObjects(fileOperationTools).build();
        fileToolCallbacks = fileProvider.getToolCallbacks();

        MethodToolCallbackProvider weatherProvider = MethodToolCallbackProvider.builder()
                .toolObjects(weatherService).build();
        weatherToolCallbacks = weatherProvider.getToolCallbacks();
    }

    /** 获取所有工具（MCP + 内置） */
    public ToolCallback[] getAllTools() {
        List<ToolCallback> all = new ArrayList<>();
        all.addAll(registry.getAllToolCallbacks());
        all.addAll(Arrays.asList(fileToolCallbacks));
        all.addAll(Arrays.asList(weatherToolCallbacks));
        return all.toArray(ToolCallback[]::new);
    }

    /** 仅文件操作工具 */
    public ToolCallback[] getFileTools() {
        return fileToolCallbacks;
    }
}
```

---

## 5. Admin API

```java
@Slf4j
@RestController
@RequestMapping("/admin/mcp")
@RequiredArgsConstructor
public class McpAdminController {

    private final McpServerConfigService configService;
    private final McpRegistry registry;
    private final McpTransportFactory transportFactory;

    // 5.1 列表
    @GetMapping("/config")
    public BaseResult<List<McpServerConfig>> list() {
        return BaseResult.newSuccess(configService.listAllOrdered());
    }

    // 5.2 新增
    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<McpServerConfig> create(@Valid @RequestBody McpServerConfig cfg) {
        cfg.setId(null);
        configService.save(cfg);
        // 尝试连接验证
        try {
            validateConnection(cfg);
            log.info("新增 MCP 配置并验证通过: {}", cfg.getName());
        } catch (Exception e) {
            // 保存成功但连接失败，标记为禁用
            cfg.setEnabled(false);
            configService.updateById(cfg);
            return BaseResult.newSuccess(cfg, "配置已保存，但连接测试失败: " + e.getMessage());
        }
        return BaseResult.newSuccess(cfg);
    }

    // 5.3 编辑
    @PutMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<McpServerConfig> update(@PathVariable Long id,
                                                @Valid @RequestBody McpServerConfig cfg) {
        cfg.setId(id);
        configService.updateById(cfg);
        registry.refresh(id);  // 热刷新
        return BaseResult.newSuccess(cfg);
    }

    // 5.4 删除
    @DeleteMapping("/config/{id}")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<Void> delete(@PathVariable Long id) {
        McpServerConfig cfg = configService.getById(id);
        if (cfg == null) return BaseResult.newError("配置不存在");
        registry.remove(cfg.getName());  // 关闭连接 + 移除
        configService.removeById(id);
        return BaseResult.newSuccess();
    }

    // 5.5 测试连接
    @PostMapping("/test")
    public BaseResult<Void> testConnection(@Valid @RequestBody McpServerConfig cfg) {
        try {
            validateConnection(cfg);
            return BaseResult.newSuccess("连接成功");
        } catch (Exception e) {
            return BaseResult.newError("连接失败: " + e.getMessage());
        }
    }

    // 5.6 刷新全部
    @PostMapping("/refresh")
    public BaseResult<Void> refreshAll() {
        registry.refreshAll();
        return BaseResult.newSuccess();
    }

    // 5.7 获取当前工具列表
    @GetMapping("/tools")
    public BaseResult<List<Map<String, String>>> listTools() {
        List<ToolCallback> callbacks = registry.getAllToolCallbacks();
        List<Map<String, String>> result = callbacks.stream().map(tc -> {
            ToolDefinition def = tc.getToolDefinition();
            return Map.of("name", def.name(), "description", def.description());
        }).collect(Collectors.toList());
        return BaseResult.newSuccess(result);
    }

    // ===== 私有 =====
    private void validateConnection(McpServerConfig cfg) {
        McpSyncClient client = transportFactory.create(cfg);
        try {
            client.initialize();
            int toolCount = client.listTools().size();
            log.info("MCP 连接测试成功: {}, 工具数: {}", cfg.getName(), toolCount);
        } finally {
            client.close();
        }
    }
}
```

---

## 6. 当前 AgentController 改造

去掉 McpToolManager 中的 `afterPropertiesSet()` 后，AgentController 无需修改（它已经通过 `@Autowired McpToolManager` 使用，`initDearAgent` 中调用 `mcpToolManager.getAllTools()` 每次都会实时组装）。

关键变动：
- AgentController **不再实现** `InitializingBean`（已移除）
- 运行时 `getAllTools()` 每次从 McpRegistry 拉取最新 MCP 工具 + 内置工具

---

## 7. 前端管理界面交互

### 7.1 页面路由建议

```
/admin/mcp           → MCP 服务器列表
/admin/mcp/create    → 新增 MCP 服务器
/admin/mcp/:id/edit  → 编辑 MCP 服务器
```

### 7.2 表单字段

| 字段 | 类型 | 说明 |
|------|------|------|
| name | 文本 | 唯一标识，如 my-custom-tool |
| label | 文本 | 展示名称 |
| transport | 下拉 | http / stdio |
| mcp_url | 文本 | http 模式时必填 |
| api_key | 密码 | http 模式 Bearer Token |
| command | 文本 | stdio 模式时必填，如 npx, python |
| args | 文本 | 命令参数，每行一个 |
| env | KV 编辑 | 环境变量键值对 |
| timeout_sec | 数字 | 超时秒数，默认 300 |
| enabled | 开关 | 是否启用 |

**表单项联动**：选择 transport = http 时隐藏 command/args/env 字段；选择 stdio 时隐藏 mcp_url/api_key 字段。

### 7.3 常用 Stdio 示例

```json
// MCP 文件系统工具
{
  "name": "mcp-filesystem",
  "label": "文件系统操作",
  "transport": "stdio",
  "command": "npx",
  "args": "[\"-y\", \"@anthropic/mcp-filesystem\", \"/path/to/allowed\"]",
  "enabled": true
}

// MCP Playwright（浏览器自动化）
{
  "name": "mcp-playwright",
  "label": "浏览器自动化",
  "transport": "stdio",
  "command": "npx",
  "args": "[\"-y\", \"@anthropic/mcp-playwright\"]",
  "enabled": false
}

// Python 自定义 MCP 服务
{
  "name": "my-python-mcp",
  "label": "Python 数据查询服务",
  "transport": "stdio",
  "command": "python",
  "args": "[\"path/to/mcp_server.py\"]",
  "env": "{\"PYTHONPATH\": \"/opt/myapp\", \"LOG_LEVEL\": \"DEBUG\"}",
  "enabled": true
}
```

---

## 8. 平滑迁移步骤

| 步骤 | 内容 | 影响 |
|------|------|------|
| 1 | 创建 `mcp_server_config` 表，插入 Tavily 种子数据 | 无 |
| 2 | 新增 `McpServerConfig` 实体 + Mapper + Service | 无 |
| 3 | 实现 `McpTransportFactory` + `McpClientHolder` + `McpRegistry` | 无 |
| 4 | 重写 `McpToolManager`（去掉 `InitializingBean`，`@PostConstruct` 只初始化内置工具） | McpToolManager API 不变 |
| 5 | 添加 `McpAdminController` | 新增 API，不影响现有 |
| 6 | 编译验证 + 启动验证 | 确认 Tavily 通过新体系正常加载 |
| 7 | 验证 `GET /admin/mcp/tools` 返回 Tavily 工具列表 | 手工测试 |
| 8 | 验证 `POST /admin/mcp/test` 测试连接 | 手工测试 |
| 9 | 验证对话流 `GET /agent/chat/stream` 正常使用搜索 | 端到端 |
| 10 | 验证前端管理界面对接 | 按需 |

---

## 9. 关键设计要点

1. **懒加载**：启动时不初始化任何 MCP 连接，只有首次 `getOrCreate()` 时才创建，避免启动依赖外部服务
2. **失败隔离**：`getAllToolCallbacks()` 中单个 MCP 加载失败只跳过该服务，不影响其他 MCP 和内置工具
3. **同步锁**：`refresh()` 和 `refreshAll()` 是 `synchronized` 的，防止并发关闭/创建冲突
4. **资源释放**：`McpClientHolder` 实现了 `AutoCloseable`，`McpRegistry.destroy()` 在应用关闭时清理所有连接
5. **验证机制**：前端可先 `POST /admin/mcp/test` 验证配置正确后再保存
