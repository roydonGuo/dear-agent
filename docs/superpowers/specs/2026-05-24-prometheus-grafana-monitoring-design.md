# Prometheus + Grafana 可观测性监控设计

## 目标

为 dear-agent 引入 Prometheus + Grafana 实现 JVM 基础指标和 HTTP 业务指标的可视化监控，暂不需要告警。

## 架构

```
Spring Boot App (520) ──/actuator/prometheus──> Prometheus (9090) ──> Grafana (3000)
```

数据流：Micrometer 采集指标 → Actuator 暴露 /actuator/prometheus → Prometheus 每 15s 抓取 → Grafana 可视化

## 改动清单

### 1. 依赖 (`app/pom.xml`)

- `spring-boot-starter-actuator` — 提供 /actuator 端点
- `micrometer-registry-prometheus` — 将 Micrometer metrics 输出为 Prometheus text format

### 2. 应用配置 (`application.yml`)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  endpoint:
    health:
      show-details: always
```

### 3. Docker Compose (`docker-compose.monitoring.yml`)

- Prometheus 容器：挂载 prometheus.yml，每 15s 抓取 host.docker.internal:520
- Grafana 容器：预配 Prometheus 数据源 + Spring Boot 3.x Dashboard

### 4. Prometheus 配置 (`config/prometheus.yml`)

- scrape_interval: 15s
- 单一 job 抓取 dear-agent 的 /actuator/prometheus

### 5. Grafana 预置 (`config/grafana/`)

- 数据源: Prometheus (自动发现)
- Dashboard: Spring Boot 3.x 通用 JVM Dashboard (导入 grafana.com 模板)

## 监控指标覆盖

| 类别 | 指标 |
|------|------|
| JVM 内存 | jvm_memory_used_bytes, jvm_memory_max_bytes (heap + non-heap) |
| JVM GC | jvm_gc_pause_seconds_count, jvm_gc_pause_seconds_sum |
| JVM 线程 | jvm_threads_live_threads, jvm_threads_daemon_threads |
| JVM CPU | process_cpu_usage, system_cpu_usage |
| HTTP | http_server_requests_seconds_count, http_server_requests_seconds_sum, http_server_requests_seconds_max |
| 连接池 | hikaricp_connections_active, hikaricp_connections_pending |

## 不涉及

- 告警规则（用户明确不需要）
- 自定义业务埋点（后续按需追加）
- MySQL/Redis/ES 指标采集（后续按需追加）
