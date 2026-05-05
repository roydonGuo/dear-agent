# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project (all modules)
mvn clean compile

# Package as JAR
mvn clean package -DskipTests

# Run locally
mvn spring-boot:run -pl app

# Run with dev profile
mvn spring-boot:run -pl app -Dspring.profiles.active=dev

# Run a specific module test
mvn test -pl module-session -Dtest=AiSessionServiceImplTest
```

## Project Structure (Multi-Module)

```
dear-agent/
├── pom.xml                             # Parent POM (dependency management)
├── app/                                # Boot module (@SpringBootApplication)
│   └── src/main/resources/application.yml
├── module-common/                      # Shared: BaseResult, AgentResponse, prompts, AgentTaskManager, domain models
├── module-config/                      # Configuration: CorsConfig, component configs
├── module-session/                     # Session system: AiSession entity, Mapper, Service, VO/DTO
├── module-model/                       # AI model layer: TTS (AlibabaTtsService, AgentVoiceStreamService)
├── module-tool/                        # Tool system: FileOperationTools, WeatherService, McpToolManager
├── module-agent/                       # Agent core: BaseAgent, DearAgent, ReactAgent
├── module-web/                         # Controller layer: unified API endpoints
└── module-knowledge/                   # Knowledge base (RAG) — placeholder for future
```

## Module Dependencies

```
app → module-web
module-web → module-agent, module-session, module-model
module-agent → module-tool, module-session, module-model, module-common
module-tool → module-common, module-config
module-session → module-common
module-model → module-common, module-config
module-config → module-common
module-knowledge → module-common, module-model
```

## Tech Stack

- **Framework**: Spring Boot 3.5.6, Spring AI 1.1.0, Spring AI Alibaba 1.1.2.0
- **AI Models**: DashScope (Aliyun) compatible models via OpenAI-compatible API (default: glm-5)
- **Search**: Tavily MCP (Model Context Protocol) for web search
- **TTS**: Aliyun DashScope qwen3-tts-flash for streaming voice synthesis
- **Database**: MySQL + MyBatis-Plus, Redis (Redisson) for distributed task locking
- **Other**: Playwright, Lombok, FastJSON2

## Key Architecture

### Agent Hierarchy

- **`BaseAgent`** (abstract, `module-agent`) — Base class with: chat history loading from DB, persistent ChatMemory creation, concurrent task checking (via AgentTaskManager), recommendation generation, and session saving support. Defines `execute(conversationId, question)` contract.
- **`DearAgent`** (*extends* `BaseAgent`, `module-agent`) — Primary agent used in production. Supports: multi-round tool calling (Tavily search + file ops), thinking/reasoning extraction from models, search reference collection, recommendation generation, persistent session storage. Built via Builder pattern.
- **`WebSearchReactAgent`** (*extends* `BaseAgent`, `module-agent`) — Simplified web search agent without thinking extraction.
- **`ReactAgent`** (`module-agent`) — Standalone react agent with no persistence layer. Has both streaming and non-streaming modes.

### Tool System

Tools are Spring AI `ToolCallback` instances injected into agents:

1. **Tavily Web Search** — MCP client connecting to `https://mcp.tavily.com/mcp/`, initialized in `McpToolManager` (`module-tool`)
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

### TTS / Voice (`module-model`)

- **`AlibabaTtsService`** — Calls DashScope TTS API, returns `Flux<byte[]>` audio data parsed from SSE
- **`AgentVoiceStreamService`** — Wraps an agent text stream, splits into sentences, queues TTS requests, interleaves `type: text`, `type: audio`, and `type: done` SSE events
- **`RealtimeVoiceAgentService`** — Alternative voice agent using ChatClient directly with `windowUntil` sentence splitting

### Session & Memory (`module-session`)

- **AiSession** entity maps to `ai_session` table storing: question, answer, thinking, tools, reference, recommend, response times
- **AiSessionService** saves questions and updates answers, loads chat history by sessionId
- **ChatMemory** (MessageWindowChatMemory) holds in-memory conversation context, loaded from DB on init (in `module-agent`)

### Task Management (`module-common`)

- **AgentTaskManager** uses Redis + Redisson for distributed task lifecycle:
  - Redis bucket with TTL tracks which instance holds a task
  - Redis Pub/Sub for cross-instance stop signals
  - Concurrent task prevention per conversationId
  - TTL refresh every 5 minutes

### Distribution of Classes

| Module | Key Classes |
|--------|-------------|
| `module-common` | `BaseResult`, `AgentResponse`, `AgentTaskManager`, `BaseAgentPrompts`, `ReactAgentPrompts`, `PlanExecutePrompts`, `AgentState`, `RoundState`, `RoundMode`, `SearchResult`, `SimpleReactResult` |
| `module-config` | `CorsConfig` |
| `module-session` | `AiSession`, `AiSessionMapper`, `AiSessionService`, `AiSessionServiceImpl`, `SaveQuestionRequest`, `UpdateAnswerRequest`, `MessageVO`, `PageResult`, `SessionDetailVO`, `SessionListVO` |
| `module-model` | `AlibabaTtsService`, `TtsConfig`, `AgentVoiceStreamService`, `RealtimeVoiceAgentService` |
| `module-tool` | `FileOperationTools`, `WeatherService`, `McpToolManager` |
| `module-agent` | `BaseAgent`, `DearAgent`, `WebSearchReactAgent`, `ReactAgent` |
| `module-web` | `AgentController`, `SessionController`, `VoiceAgentController`, `AuthController` |
| `app` | `DearAgentApplication`, `application.yml`, `application-dev.yml`, `application-pro.yml` |

### API Endpoints

| Endpoint | Module | Description |
|----------|--------|-------------|
| `GET /agent/chat/stream` | `module-web` | Main chat SSE |
| `GET /agent/stop` | `module-web` | Stop an executing agent |
| `GET /session/{id}` | `module-web` | Session detail |
| `GET /session/list` | `module-web` | Session list (paginated) |
| `DELETE /session/{id}` | `module-web` | Delete session |
| `GET /api/agent/stream-with-voice` | `module-web` | Voice agent SSE |
| `GET /api/agent/quick` | `module-web` | Quick voice chat |
| `GET /api/agent/health` | `module-web` | Health check |

### Response Format (SSE)

Each event is a JSON string with `type` field: `text`, `thinking`, `reference`, `recommend`, `error`, `done`, `audio`.

### Configuration

Key settings in `application.yml` (`app/src/main/resources/`):
- `spring.ai.openai.*` — AI model connection (DashScope-compatible URL)
- `spring.datasource.*` — MySQL connection
- `spring.data.redis.*` — Redis for task management
- `tavily.api-key / tavily.mcp-url` — Web search
- `alibaba.dashscope.tts.*` — TTS settings
