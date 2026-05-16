# 知识库文件模块 — Vue 3 前端对接技术参考

## 1. API 接口总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/knowledge-file/tree?baseId={id}` | 查询文件树 |
| `GET` | `/knowledge-file/{id}` | 获取文件详情 |
| `POST` | `/knowledge-file` | 创建文件/文件夹 |
| `PUT` | `/knowledge-file/{id}` | 更新文件/文件夹 |
| `DELETE` | `/knowledge-file/{id}` | 删除文件/文件夹（级联） |


## 2. 统一响应格式

```ts
interface BaseResult<T> {
  code: number    // 200=成功, 400=校验失败, 500=错误
  message: string
  data: T
}
```

## 3. 类型定义

```ts
// === 树节点（/tree 返回） ===
interface KnowledgeFileTreeNode {
  id: number
  name: string
  type: 'folder' | 'file'          // folder=文件夹, file=文件
  fileType: string | null           // md / pdf / jpg / mp4 等
  content: string | null
  createTime: string
  updateTime: string
  children: KnowledgeFileTreeNode[] // 文件夹时递归嵌套
}

// === 文件详情（GET /{id} 返回） ===
interface KnowledgeFileResp {
  id: number
  baseId: number
  parentId: number
  ancestors: string
  name: string
  fileType: 'folder' | 'file'
  mineType: string | null
  content: string | null
  createTime: string
  updateTime: string
}

// === 创建/更新请求体 ===
interface KnowledgeFileRequest {
  baseId: number                      // 必填
  parentId?: number                   // 父节点ID，默认0=根节点
  name?: string                       // 最长20字符
  fileType: 'folder' | 'file'         // 必填
  mineType?: string                   // 最长32字符，如 "md", "pdf"
  content?: string                    // 文件内容
}
```

## 4. API 函数封装

```ts
import axios from 'axios'

const BASE = '/knowledge-file'

// 查询文件树
export function fetchFileTree(baseId: number): Promise<BaseResult<KnowledgeFileTreeNode[]>> {
  return axios.get(`${BASE}/tree`, { params: { baseId } }).then(r => r.data)
}

// 获取文件详情
export function fetchFileDetail(id: number): Promise<BaseResult<KnowledgeFileResp>> {
  return axios.get(`${BASE}/${id}`).then(r => r.data)
}

// 创建
export function createFile(req: KnowledgeFileRequest): Promise<BaseResult<KnowledgeFileResp>> {
  return axios.post(BASE, req).then(r => r.data)
}

// 更新
export function updateFile(id: number, req: KnowledgeFileRequest): Promise<BaseResult<KnowledgeFileResp>> {
  return axios.put(`${BASE}/${id}`, req).then(r => r.data)
}

// 删除
export function deleteFile(id: number): Promise<BaseResult<string>> {
  return axios.delete(`${BASE}/${id}`).then(r => r.data)
}
```

## 5. Vue 3 组合式 API 示例

### 5.1 树数据加载

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchFileTree, createFile, updateFile, deleteFile } from '@/api/knowledge-file'

const props = defineProps<{ baseId: number }>()
const treeData = ref<KnowledgeFileTreeNode[]>([])
const loading = ref(false)

async function loadTree() {
  loading.value = true
  try {
    const res = await fetchFileTree(props.baseId)
    if (res.code === 200) {
      treeData.value = res.data
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadTree)
</script>
```

### 5.2 创建文件夹（右键菜单 -> 新建文件夹）

```ts
async function handleCreateFolder(parentId: number, name: string) {
  const req: KnowledgeFileRequest = {
    baseId: props.baseId,
    parentId,
    name,
    fileType: 'folder',
  }
  const res = await createFile(req)
  if (res.code === 200) {
    await loadTree()  // 刷新树
    return res.data
  }
  throw new Error(res.message)
}
```

### 5.3 创建文件

```ts
async function handleCreateFile(parentId: number, name: string, mineType: string, content: string) {
  const req: KnowledgeFileRequest = {
    baseId: props.baseId,
    parentId,
    name,
    fileType: 'file',
    mineType,
    content,
  }
  const res = await createFile(req)
  if (res.code === 200) {
    await loadTree()
    return res.data
  }
  throw new Error(res.message)
}
```

### 5.4 更新节点（重命名/编辑内容）

```ts
async function handleUpdate(id: number, updates: Partial<KnowledgeFileRequest>) {
  const current = await fetchFileDetail(id)
  if (current.code !== 200) throw new Error(current.message)

  const req: KnowledgeFileRequest = {
    baseId: current.data.baseId,
    parentId: current.data.parentId,
    fileType: current.data.fileType,
    name: updates.name ?? current.data.name,
    mineType: updates.mineType ?? current.data.mineType,
    content: updates.content ?? current.data.content,
  }
  const res = await updateFile(id, req)
  if (res.code === 200) {
    await loadTree()
    return res.data
  }
  throw new Error(res.message)
}
```

### 5.5 删除节点（级联删除）

```ts
async function handleDelete(id: number) {
  if (!confirm('删除后将无法恢复，确认删除？')) return
  const res = await deleteFile(id)
  if (res.code === 200) {
    await loadTree()
  } else {
    alert(res.message)
  }
}
```

## 6. 树组件集成参考

可使用第三方树组件（Element Plus `el-tree`、Naive UI `n-tree`、PrimeVue `Tree`），关键适配点：

```vue
<template>
  <el-tree
    :data="treeData"
    :props="{ label: 'name', children: 'children' }"
    node-key="id"
    :expand-on-click-node="false"
    @node-click="handleNodeClick"
    @node-contextmenu="handleContextMenu"
  />
</template>
```

树节点 label 使用 `name`，children 使用 `children`（与后端一致，无需 `node-key` 转换）。

### 右键菜单操作节点

```ts
const selectedNode = ref<KnowledgeFileTreeNode | null>(null)
const contextMenuVisible = ref(false)
const contextMenuPos = ref({ x: 0, y: 0 })

function handleContextMenu(event: MouseEvent, node: KnowledgeFileTreeNode) {
  event.preventDefault()
  selectedNode.value = node
  contextMenuPos.value = { x: event.clientX, y: event.clientY }
  contextMenuVisible.value = true
}
```

## 7. 前端校验规则（对齐后端验证）

| 字段 | 规则 |
|------|------|
| `baseId` | 必填（`required`） |
| `name` | 最长 20 字符 |
| `fileType` | 必填，值只能为 `folder` 或 `file` |
| `mineType` | 最长 32 字符 |

```ts
const rules = {
  baseId: [{ required: true, message: '知识库ID不能为空', trigger: 'blur' }],
  name: [{ max: 20, message: '名称最长20个字符', trigger: 'blur' }],
  fileType: [
    { required: true, message: '类型不能为空', trigger: 'change' },
    { validator: (_rule: any, value: string) => ['folder', 'file'].includes(value), message: '类型必须为folder或file' },
  ],
  mineType: [{ max: 32, message: '文件格式最长32个字符', trigger: 'blur' }],
}
```

## 8. 典型交互流程

```
文件树页面
├── 初始化 → GET /tree?baseId=xxx → 渲染树
├── 点击文件夹 → 展开/折叠子节点（数据已在树中）
├── 点击文件 → GET /{id} → 右侧内容面板展示
├── 新建文件夹 → 弹窗输入名称 → POST → 刷新树
├── 新建文件 → 弹窗输入名称+格式 → POST → 刷新树
├── 重命名 → 双击/右键 → PUT → 刷新树
├── 编辑内容 → 编辑器修改 → PUT（保存时）→ 刷新树
└── 删除 → 确认对话框 → DELETE → 刷新树
```
