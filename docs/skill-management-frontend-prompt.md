# Vue3 Skill 管理前端 — 技术实现参考

## 项目背景

后端已实现完整的 Skill 管理系统，需要前端 Vue3 管理界面。后端 Spring Boot 端口 520，前端 Vite devServer 端口 3000 通过 proxy 转发。

## 一、后端 API 参考

Base URL: `http://localhost:520`（开发时 Vite proxy 自动转发 `/api/*` → `localhost:520`）

统一响应格式 `BaseResult<T>`:
```json
{ "code": 200, "message": "", "data": T }
```
code 200 = 成功，非 200 = 错误（message 中有错误信息）。

### 端点清单

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/skills` | Skill 列表 | - |
| GET | `/skills/{id}` | Skill 详情 | - |
| POST | `/skills` | 新增 Skill | Skill JSON |
| PUT | `/skills/{id}` | 更新 Skill | Skill JSON |
| DELETE | `/skills/{id}` | 删除 Skill | - |
| PATCH | `/skills/{id}/toggle` | 启用/禁用 | - |
| POST | `/skills/refresh` | 刷新加载器 | - |
| GET | `/skills/tools` | 已加载工具列表 | - |

## 二、TypeScript 类型定义

```typescript
// src/types/skill.ts

type SkillType = 'FUNCTION' | 'MCP' | 'TOOL'

interface SkillParameter {
  name: string
  type: 'string' | 'int' | 'number' | 'boolean' | 'object' | 'array'
  required: boolean
  description: string
  defaultValue?: string
}

interface Skill {
  id: string
  name: string
  description: string
  version: string
  author: string
  type: SkillType
  entry: string
  parameters: SkillParameter[]
  enabled: boolean
  createTime: string  // ISO format
  updateTime: string
}

interface BaseResult<T> {
  code: number
  message: string
  data: T
}

interface LoadedTool {
  name: string
  description: string
}
```

## 三、路由设计

```
/skills              → SkillList.vue   (列表页)
/skills/create       → SkillEdit.vue   (新增页)
/skills/:id/edit     → SkillEdit.vue   (编辑页，复用同一个组件)
```

## 四、API 层（axios）

```typescript
// src/api/skill.ts
import axios from 'axios'
import type { Skill, BaseResult, LoadedTool } from '@/types/skill'

const http = axios.create({ baseURL: '' })

export const fetchSkills      = ()       => http.get<BaseResult<Skill[]>>('/api/skills')
export const fetchSkill       = (id: string) => http.get<BaseResult<Skill>>(`/api/skills/${encodeURIComponent(id)}`)
export const createSkill      = (s: Skill)   => http.post<BaseResult<Skill>>('/api/skills', s)
export const updateSkill      = (id: string, s: Skill) => http.put<BaseResult<Skill>>(`/api/skills/${encodeURIComponent(id)}`, s)
export const deleteSkill      = (id: string) => http.delete<BaseResult<null>>(`/api/skills/${encodeURIComponent(id)}`)
export const toggleSkill      = (id: string) => http.patch<BaseResult<Skill>>(`/api/skills/${encodeURIComponent(id)}/toggle`)
export const refreshSkills    = ()       => http.post<BaseResult<{ loaded: number }>>('/api/skills/refresh')
export const fetchLoadedTools = ()       => http.get<BaseResult<LoadedTool[]>>('/api/skills/tools')
```

封装建议：为每个 API 函数做 `.then(res => res.data)` 解包，根据 `code` 判断成功/失败，统一错误提示。

## 五、组件树与功能要求

### 1. SkillList.vue（列表页）

**功能：**
- 表格展示所有 Skill，列：name、id、type（标签/badge）、version、author、enabled（开关图标）、updateTime、操作按钮
- 顶部工具栏：`[+ 新建 Skill]` 按钮、`[刷新加载器]` 按钮（显示已加载工具数）
- 每行操作：编辑、删除（二次确认弹窗）、启用/禁用
- type 列用不同颜色 badge：FUNCTION=蓝色、MCP=绿色、TOOL=橙色
- enabled 列用 el-switch 或自定义 toggle，点击直接调 PATCH 接口切换
- 删除用 el-popconfirm 或 Modal 二次确认
- 表格上方显示 `已加载工具: N 个` 统计卡片（调用 GET /api/skills/tools）

**交互细节：**
- 刷新加载器后更新工具计数
- toggle 操作即时更新当前行 enabled 字段，无需重新请求列表
- 删除成功后从列表中移除该行
- 进入页面时自动拉取列表 + 已加载工具数

### 2. SkillEdit.vue（新增/编辑页，共用一个组件）

**路由区分：** 有 `:id` 则编辑模式（先 GET /api/skills/:id 加载数据），无 `:id` 则创建模式。

**表单字段：**
- `id` — 文本输入（创建模式下必填，只允许英文/数字/短横线，编辑模式下只读）
- `name` — 文本输入（必填）
- `description` — textarea（必填，提示：会作为 ToolDescription 供模型理解）
- `version` — 文本输入（默认 "1.0.0"）
- `author` — 文本输入
- `type` — 下拉选择：FUNCTION / MCP / TOOL（必填）
- `entry` — 文本输入（必填，下方根据 type 显示不同 placeholder 提示）
  - FUNCTION: `beanName.methodName` 例: `fileOperationTools.readFile`
  - MCP: `serverName:toolName` 例: `tavily:tavily_search`
  - TOOL: 脚本路径 例: `/usr/bin/python3`
- `enabled` — 开关（默认开启）
- `parameters` — 动态列表，每行含：
  - name（文本）
  - type（下拉：string/int/number/boolean/object/array）
  - required（复选框）
  - description（文本）
  - defaultValue（文本，选填）
  - 删除按钮（行末）
  - `[+ 添加参数]` 按钮在列表下方

**type 字段切换行为：** 切换 type 时 entry 的 placeholder 实时变化。

**底部按钮：** `[保存]` `[取消]`

**表单校验：**
- id: 必填，pattern `[a-z0-9-]+`（创建模式）
- name: 必填
- description: 必填
- entry: 必填

**提交：**
- 创建: POST /api/skills
- 编辑: PUT /api/skills/:id
- 成功后 router.push('/skills')
- 失败弹错误提示

## 六、Vite 配置（proxy）

```typescript
// vite.config.ts
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api':    { target: 'http://localhost:520', changeOrigin: true },
      '/agent':  { target: 'http://localhost:520', changeOrigin: true },
      '/session':{ target: 'http://localhost:520', changeOrigin: true },
      '/mcp':    { target: 'http://localhost:520', changeOrigin: true },
      '/model':  { target: 'http://localhost:520', changeOrigin: true },
    },
  },
})
```

## 七、UI 风格建议

- 整体：简洁管理后台风格，浅色背景 + 白色卡片
- 列表页使用标准 table（带边框/斑马纹均可）
- type badge 配色：FUNCTION → `#4f46e5`（indigo）、MCP → `#059669`（emerald）、TOOL → `#ea580c`（orange）
- 表格操作列放 3 个紧凑按钮/图标：编辑(✏️)、启用/禁用(toggle)、删除(🗑️)
- 表单标签对齐，parameters 子表使用 card 嵌套样式，略缩进

## 八、技术栈

- Vue 3 + `<script setup lang="ts">` + Composition API
- Vue Router 4
- Axios（已配置 proxy，baseURL 为空）
- Vite 6
- 可选 UI 库：Element Plus（推荐，el-table/el-form/el-dialog 现成组件）或纯手写 CSS
