# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project
mvn clean compile

# Package as JAR
mvn clean package

# Run locally
mvn spring-boot:run

# Run with dev profile
mvn spring-boot:run -Dspring.profiles.active=dev
```

## Project Overview

A Live2D-powered companion Agent built with Spring Boot 3.5 + Java 21. Users interact via a web frontend with an AI agent that supports web search, file operations, conversational memory, and realtime TTS voice output.

## Tech Stack

- **Framework**: Spring Boot 3.5.6, Spring AI 1.1.0, Spring AI Alibaba 1.1.2.0
- **AI Models**: DashScope (Aliyun) compatible models via OpenAI-compatible API (default: glm-5)
- **Search**: Tavily MCP (Model Context Protocol) for web search
- **TTS**: Aliyun DashScope qwen3-tts-flash for streaming voice synthesis
- **Database**: MySQL + MyBatis-Plus, Redis (Redisson) for distributed task locking
- **Other**: Playwright, Lombok, FastJSON2

## Key Architecture

### Agent Hierarchy

- **`BaseAgent`** (abstract) — Base class with: chat history loading from DB, persistent ChatMemory creation, concurrent task checking (via AgentTaskManager), recommendation generation, and session saving support. Defines `execute(conversationId, question)` contract.
- **`DearAgent`** (*extends* `BaseAgent`) — Primary agent used in production. Supports: multi-round tool calling (Tavily search + file ops), thinking/reasoning extraction from models, search reference collection, recommendation generation, persistent session storage. Built via Builder pattern.
- **`WebSearchReactAgent`** (*extends* `BaseAgent`) — Simplified web search agent without thinking extraction.
- **`ReactAgent`** — Standalone (not extending BaseAgent). Simpler react agent with no persistence layer. Has both streaming and non-streaming modes.

### Tool System

Tools are Spring AI `ToolCallback` instances injected into agents:

1. **Tavily Web Search** — MCP client connecting to `https://mcp.tavily.com/mcp/`, initialized in `AgentController.afterPropertiesSet()`
2. **FileOperationTools** — `@Tool`-annotated Spring service providing: `bash` (shell exec), `read_file`, `write_file`, `edit_file`
3. **WeatherService** — `@Tool`-annotated demo weather lookup

### Tool Calling Flow (DearAgent)

```
User Question → ChatClient.stream().chatResponse() 
  → processChunk: emit text, detect tool_calls
  → finishRound: if TOOL_CALL → executeToolCalls (parallel via boundedElastic)
  → completeToolCall (all done) → scheduleRound (next iteration)
  → If no tool_calls → final answer + references + recommendations + done
```

### TTS / Voice

- **`AlibabaTtsService`** — Calls DashScope TTS API, returns `Flux<byte[]>` audio data parsed from SSE
- **`AgentVoiceStreamService`** — Wraps an agent text stream, splits into sentences, queues TTS requests, interleaves `type: text`, `type: audio`, and `type: done` SSE events
- **`RealtimeVoiceAgentService`** — Alternative voice agent using ChatClient directly with `windowUntil` sentence splitting

### Session & Memory

- **AiSession** entity maps to `ai_session` table storing: question, answer, thinking, tools, reference, recommend, response times
- **AiSessionService** saves questions and updates answers, loads chat history by sessionId
- **ChatMemory** (MessageWindowChatMemory) holds in-memory conversation context, loaded from DB on init

### Task Management

- **AgentTaskManager** uses Redis + Redisson for distributed task lifecycle:
  - Redis bucket with TTL tracks which instance holds a task
  - Redis Pub/Sub for cross-instance stop signals
  - Concurrent task prevention per conversationId
  - TTL refresh every 5 minutes

### API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /agent/chat/stream` | Main chat SSE: query + conversationId + think + webSearch + voiceOutput |
| `GET /agent/stop` | Stop an executing agent |
| `GET /session/{id}` | Session detail |
| `GET /session/list` | Session list (paginated) |
| `DELETE /session/{id}` | Delete session |
| `GET /api/agent/stream-with-voice` | Voice agent SSE |
| `GET /api/agent/quick` | Quick voice chat |
| `GET /api/agent/health` | Health check |

### Response Format (SSE)

Each event is a JSON string with `type` field: `text`, `thinking`, `reference`, `recommend`, `error`, `done`, `audio`.

### Configuration

Key settings in `application.yml`:
- `spring.ai.openai.*` — AI model connection (DashScope-compatible URL)
- `spring.datasource.*` — MySQL connection
- `spring.data.redis.*` — Redis for task management
- `tavily.api-key / tavily.mcp-url` — Web search
- `alibaba.dashscope.tts.*` — TTS settings
