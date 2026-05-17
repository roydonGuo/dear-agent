**# 知识库文件树拖拽排序 — Vue 3 前端技术参考

## 1. 新增 / 变更 API

### 1.1 新增接口

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `PUT` | `/knowledge-file/reorder` | `baseId` (query) + `ReorderRequest` (body) | 拖拽排序 |

请求体：
```json
{
  "id": 123,
  "targetParentId": 456,
  "position": 2
}
```

成功响应 `BaseResult<Void>`（code=200）。

失败场景：
- 500: 节点不存在 / 不能将文件夹移动到自己的子文件夹下（循环引用）
- 500: 目标父节点不存在

### 1.2 已有接口响应新增字段

`GET /knowledge-file/tree` 和 `GET /knowledge-file/{id}` 返回数据新增 `sort` 字段：

```json
{
  "id": 123,
  "name": "需求文档.md",
  "type": "file",
  "sort": 200,
  "children": []
}
```

`sort` 为整数，同级节点按 `sort ASC` 排列（间隙 100：100, 200, 300...）。新建节点自动分配末尾值（最大 sort + 100）。

---

## 2. TypeScript 类型更新

```ts
// === 树节点（新增 sort） ===
interface KnowledgeFileTreeNode {
  id: number
  name: string
  type: 'folder' | 'file'
  fileType: string | null
  content: string | null
  storagePath: string | null
  fileSize: number | null
  sort: number                     // 新增：排序值，同级按此值升序
  fileUrl: string | null
  createTime: string
  updateTime: string
  children: KnowledgeFileTreeNode[]
}

// === 拖拽排序请求 ===
interface ReorderRequest {
  id: number                      // 被拖拽的节点 ID
  targetParentId: number          // 目标父节点 ID（0 = 根目录）
  position: number                // 目标位置索引（0-based，在同级现有子节点中的位置）
}

// === API 封装 ===
import axios from 'axios'

const BASE = '/knowledge-file'

export function reorderFile(baseId: number, req: ReorderRequest): Promise<BaseResult<null>> {
  return axios.put(`${BASE}/reorder`, req, { params: { baseId } }).then(r => r.data)
}
```

---

## 3. 拖拽方案选择

### 推荐：原生 HTML5 Drag & Drop

原生 API 零依赖，对树形数据结构友好，配合 `draggable` 属性和 `dragenter`/`dragover`/`drop` 事件即可实现。

**不推荐引入重型拖拽库**（如 `vuedraggable`）——需要对树节点做大量样式和行为定制，原生 API 更灵活。

### 核心事件映射

| 事件 | 在谁身上 | 做什么 |
|---|---|---|
| `dragstart` | 拖拽源节点 | 保存被拖拽节点的 `id`、`type`、当前 `parentId` 到 `dataTransfer` |
| `dragover` | 经过的节点 | 判断是否可放置（文件夹 or 同级位置），设置 `dropEffect` |
| `dragenter` | 经过的节点 | 计算 drop 位置（before / inside / after），添加视觉高亮 |
| `dragleave` | 离开的节点 | 清除视觉高亮 |
| `drop` | 目标节点 | 计算 `targetParentId` + `position`，调用 reorder API |
| `dragend` | 拖拽源节点 | 清除所有高亮状态 |

---

## 4. 拖拽位置判定

每个树节点有三个投放区域：

```
┌──────────────────────┐
│  [drop-before]       │  ← 拖入节点上方 → 同父级，position = 当前索引
├──────────────────────┤
│  📁 文件夹名称        │  ← 拖入节点内部 → targetParentId = 当前ID, position = 0
│  [drop-inside]       │     （仅文件夹接受 inside 投放）
├──────────────────────┤
│  [drop-after]        │  ← 拖入节点下方 → 同父级，position = 当前索引 + 1
└──────────────────────┘
```

判定逻辑通过鼠标在节点矩形内的 Y 坐标比例：

```ts
type DropZone = 'before' | 'inside' | 'after'

function calcDropZone(e: DragEvent, el: HTMLElement, isFolder: boolean): DropZone {
  const rect = el.getBoundingClientRect()
  const y = e.clientY - rect.top
  const ratio = y / rect.height

  if (ratio < 0.25) return 'before'
  if (ratio > 0.75) return 'after'
  if (isFolder) return 'inside'
  // 文件不可置入内部，判定为相邻位置
  return ratio < 0.5 ? 'before' : 'after'
}
```

---

## 5. 核心 Composable：`useDragSort`

```ts
// composables/useDragSort.ts
import { ref, type Ref } from 'vue'
import { reorderFile, type KnowledgeFileTreeNode, type ReorderRequest } from '@/api/knowledge-file'

export interface DragState {
  dragNode: KnowledgeFileTreeNode | null
  dragNodeParentId: number       // 拖拽节点当前的 parentId
  dropTarget: KnowledgeFileTreeNode | null
  dropZone: DropZone | null
}

export type DropZone = 'before' | 'inside' | 'after'

export function useDragSort(baseId: Ref<number>, treeData: Ref<KnowledgeFileTreeNode[]>) {
  const dragState = ref<DragState>({
    dragNode: null,
    dragNodeParentId: 0,
    dropTarget: null,
    dropZone: null,
  })
  const isReordering = ref(false)

  // ---- dragstart: 记录被拖拽节点 ----
  function onDragStart(e: DragEvent, node: KnowledgeFileTreeNode, parentId: number) {
    e.dataTransfer!.effectAllowed = 'move'
    e.dataTransfer!.setData('text/plain', String(node.id))
    // 拖拽缩略图（可选）
    if (e.dataTransfer!.setDragImage) {
      const el = e.target as HTMLElement
      e.dataTransfer!.setDragImage(el, el.offsetWidth / 2, 20)
    }

    dragState.value = {
      dragNode: node,
      dragNodeParentId: parentId,
      dropTarget: null,
      dropZone: null,
    }
  }

  // ---- dragover: 必须 preventDefault 才能触发 drop ----
  function onDragOver(e: DragEvent, node: KnowledgeFileTreeNode, parentId: number) {
    e.preventDefault()
    if (!dragState.value.dragNode || dragState.value.dragNode.id === node.id) return

    const el = e.currentTarget as HTMLElement
    const isFolder = node.type === 'folder'
    const zone = calcDropZone(e, el, isFolder)

    // 防止循环引用（前端预检）：不能拖入自己的子孙文件夹
    if (zone === 'inside' && isDescendant(dragState.value.dragNode, node)) {
      e.dataTransfer!.dropEffect = 'none'
      return
    }

    e.dataTransfer!.dropEffect = 'move'
    dragState.value.dropTarget = node
    dragState.value.dropZone = zone
  }

  // ---- dragleave: 清除高亮 ----
  function onDragLeave(e: DragEvent, node: KnowledgeFileTreeNode) {
    if (dragState.value.dropTarget?.id === node.id) {
      dragState.value.dropTarget = null
      dragState.value.dropZone = null
    }
  }

  // ---- drop: 执行排序 ----
  async function onDrop(e: DragEvent, targetNode: KnowledgeFileTreeNode, targetParentId: number) {
    e.preventDefault()
    const state = dragState.value
    if (!state.dragNode || !state.dropTarget) return

    const zone = state.dropZone!
    let request: ReorderRequest

    if (zone === 'inside') {
      // 放入文件夹内部
      request = {
        id: state.dragNode.id,
        targetParentId: targetNode.id,
        position: 0,            // 后端自动放到末尾，传 0 表示首位
      }
    } else {
      // before / after：同一父级
      const siblings = getSiblings(treeData.value, targetParentId, targetNode.id)
      const targetIndex = siblings.findIndex(n => n.id === targetNode.id)
      request = {
        id: state.dragNode.id,
        targetParentId: targetParentId,
        position: zone === 'before' ? targetIndex : targetIndex + 1,
      }
    }

    // 如果拖回原位，无视
    if (request.targetParentId === state.dragNodeParentId) {
      const origSiblings = getSiblings(treeData.value, state.dragNodeParentId, state.dragNode.id)
      const origIndex = origSiblings.findIndex(n => n.id === state.dragNode.id)
      if (request.targetParentId === state.dragNodeParentId && request.position === origIndex) {
        resetDragState()
        return
      }
      // 移动到自身之后，偏移量修正
      if (request.position > origIndex) {
        request.position--
      }
    }

    isReordering.value = true
    try {
      await reorderFile(baseId.value, request)
      // 乐观更新：前端本地调整树结构，或直接刷新
      applyOptimisticReorder(request)
    } catch (err: any) {
      // 后端校验失败（如循环引用）时刷新树以恢复正确状态
      console.error('Reordering failed:', err)
      await refreshTree()
    } finally {
      isReordering.value = false
      resetDragState()
    }
  }

  // ---- dragend: 清理 ----
  function onDragEnd() {
    resetDragState()
  }

  function resetDragState() {
    dragState.value = {
      dragNode: null,
      dragNodeParentId: 0,
      dropTarget: null,
      dropZone: null,
    }
  }

  return {
    dragState,
    isReordering,
    onDragStart,
    onDragOver,
    onDragLeave,
    onDrop,
    onDragEnd,
  }
}

// ---- 辅助函数 ----

function calcDropZone(e: DragEvent, el: HTMLElement, isFolder: boolean): DropZone {
  const rect = el.getBoundingClientRect()
  const y = e.clientY - rect.top
  const ratio = y / rect.height

  if (ratio < 0.25) return 'before'
  if (ratio > 0.75) return 'after'
  if (isFolder) return 'inside'
  return ratio < 0.5 ? 'before' : 'after'
}

/** 检查 node 是否是 target 的后代（防止循环引用 -- 前端预检） */
function isDescendant(node: KnowledgeFileTreeNode, target: KnowledgeFileTreeNode): boolean {
  function findInChildren(children: KnowledgeFileTreeNode[], id: number): boolean {
    for (const child of children) {
      if (child.id === id) return true
      if (child.children.length > 0 && findInChildren(child.children, id)) return true
    }
    return false
  }
  return findInChildren(node.children, target.id)
}

/** 获取指定父级下同级节点列表（扁平，不含 children） */
function getSiblings(
  tree: KnowledgeFileTreeNode[],
  parentId: number,
  selfId: number,
): KnowledgeFileTreeNode[] {
  if (parentId === 0) return tree

  function find(parentId: number, nodes: KnowledgeFileTreeNode[]): KnowledgeFileTreeNode[] | null {
    for (const node of nodes) {
      if (node.id === parentId) return node.children
      if (node.children.length > 0) {
        const found = find(parentId, node.children)
        if (found) return found
      }
    }
    return null
  }

  return find(parentId, tree) ?? []
}
```

---

## 6. 树节点组件 `TreeNodeItem.vue` 参考

```vue
<template>
  <div
    class="tree-node"
    :class="{
      'is-dragging': isDragSource,
      'drop-before': dropHighlight === 'before',
      'drop-inside': dropHighlight === 'inside',
      'drop-after': dropHighlight === 'after',
    }"
    :draggable="true"
    @dragstart="onDragStart($event, node, parentId)"
    @dragover="onDragOver($event, node, parentId)"
    @dragleave="onDragLeave($event, node)"
    @drop="onDrop($event, node, parentId)"
    @dragend="onDragEnd"
  >
    <!-- 拖拽手柄 -->
    <span class="drag-handle" title="拖拽排序">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="#999">
        <circle cx="9" cy="5" r="2" />
        <circle cx="15" cy="5" r="2" />
        <circle cx="9" cy="12" r="2" />
        <circle cx="15" cy="12" r="2" />
        <circle cx="9" cy="19" r="2" />
        <circle cx="15" cy="19" r="2" />
      </svg>
    </span>

    <!-- 图标 -->
    <span class="node-icon">
      <FolderIcon v-if="node.type === 'folder'" />
      <FileIcon v-else :fileType="node.fileType" />
    </span>

    <!-- 名称 -->
    <span class="node-name">{{ node.name }}</span>

    <!-- 展开子节点（仅文件夹） -->
    <div v-if="node.type === 'folder' && expanded" class="tree-children">
      <TreeNodeItem
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :parent-id="node.id"
        :drag-state="dragState"
        :base-id="baseId"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { KnowledgeFileTreeNode, DragState, DropZone } from '@/composables/useDragSort'

const props = defineProps<{
  node: KnowledgeFileTreeNode
  parentId: number          // 当前节点所属父级 ID
  dragState: DragState
  baseId: number
}>()

const emit = defineEmits<{
  dragStart: [e: DragEvent, node: KnowledgeFileTreeNode, parentId: number]
  dragOver: [e: DragEvent, node: KnowledgeFileTreeNode, parentId: number]
  dragLeave: [e: DragEvent, node: KnowledgeFileTreeNode]
  drop: [e: DragEvent, node: KnowledgeFileTreeNode, parentId: number]
  dragEnd: []
}>()

const expanded = ref(true) // 默认展开

const isDragSource = computed(() =>
  props.dragState.dragNode?.id === props.node.id
)

const dropHighlight = computed<DropZone | null>(() => {
  if (props.dragState.dropTarget?.id === props.node.id) {
    return props.dragState.dropZone
  }
  return null
})
</script>

<style scoped>
.tree-node {
  display: flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: default;
  user-select: none;
  position: relative;
}

.tree-node.is-dragging {
  opacity: 0.4;
}

.drag-handle {
  cursor: grab;
  margin-right: 6px;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.drag-handle:active {
  cursor: grabbing;
}

/* 投放区域高亮指示线 */
.drop-before::before,
.drop-after::after {
  content: '';
  position: absolute;
  left: 8px;
  right: 8px;
  height: 2px;
  background: #409eff;
  border-radius: 1px;
  pointer-events: none;
  z-index: 10;
}

.drop-before::before { top: 0; }
.drop-after::after { bottom: 0; }

/* 投放内部高亮（文件夹变色） */
.drop-inside {
  background: rgba(64, 158, 255, 0.12);
  outline: 2px solid #409eff;
  outline-offset: -2px;
}

.tree-children {
  /* 子节点在文件夹下方缩进展示 */
}
</style>
```

---

## 7. 文件树容器组件参考

```vue
<template>
  <div class="knowledge-file-tree">
    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="error" class="error">
      {{ error }}
      <button @click="loadTree">重试</button>
    </div>

    <div v-else-if="treeData.length === 0" class="empty">
      暂无文件，请上传或新建
    </div>

    <div v-else class="tree-container">
      <TreeNodeItem
        v-for="node in treeData"
        :key="node.id"
        :node="node"
        :parent-id="0"
        :drag-state="dragState"
        :base-id="baseId"
        @drag-start="onDragStart"
        @drag-over="onDragOver"
        @drag-leave="onDragLeave"
        @drop="onDrop"
        @drag-end="onDragEnd"
      />

      <!-- 拖到根目录空白区域 -->
      <div
        v-if="dragState.dragNode"
        class="root-drop-zone"
        @dragover.prevent="onRootDragOver"
        @drop="onRootDrop"
      >
        移动到根目录
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchFileTree } from '@/api/knowledge-file'
import { useDragSort } from '@/composables/useDragSort'
import type { KnowledgeFileTreeNode } from '@/types'
import TreeNodeItem from './TreeNodeItem.vue'

const props = defineProps<{ baseId: number }>()

const treeData = ref<KnowledgeFileTreeNode[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const {
  dragState,
  isReordering,
  onDragStart,
  onDragOver,
  onDragLeave,
  onDrop,
  onDragEnd,
} = useDragSort(ref(props.baseId), treeData)

async function loadTree() {
  loading.value = true
  error.value = null
  try {
    const res = await fetchFileTree(props.baseId)
    if (res.code === 200) {
      treeData.value = res.data
    } else {
      error.value = res.message
    }
  } catch (e: any) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 拖入根目录空白区域
function onRootDragOver(e: DragEvent) {
  e.preventDefault()
  e.dataTransfer!.dropEffect = 'move'
}

async function onRootDrop(e: DragEvent) {
  e.preventDefault()
  const node = dragState.value.dragNode
  if (!node) return

  const request: ReorderRequest = {
    id: node.id,
    targetParentId: 0,
    position: treeData.value.length,
  }
  // 如果已经在根目录且位置不变，忽略
  if (dragState.value.dragNodeParentId === 0) {
    const idx = treeData.value.findIndex(n => n.id === node.id)
    if (idx === request.position - 1) return // 位置不变
  }

  try {
    await reorderFile(props.baseId, request)
    await loadTree()
  } catch (e: any) {
    console.error(e)
    await loadTree()
  }
}

onMounted(loadTree)

defineExpose({ loadTree })
</script>
```

---

## 8. 交互细节要点

### 8.1 拖拽视觉反馈

| 状态 | 效果 |
|---|---|
| 拖拽源节点 | 半透明（opacity: 0.4） |
| 拖入前（before） | 节点顶部蓝色指示线 |
| 拖入后（after） | 节点底部蓝色指示线 |
| 拖入文件夹内部（inside） | 节点背景蓝色高亮 + 蓝色边框 |
| 禁止投放（循环引用） | `dropEffect = 'none'`，光标变禁止符号 |

### 8.2 前端循环引用预检

```ts
// 在 dragover 中阻止拖入自己的子孙节点（前端预检，后端也会校验）
function isDescendant(dragNode: KnowledgeFileTreeNode, target: KnowledgeFileTreeNode): boolean {
  if (dragNode.type !== 'folder') return false // 文件无子节点，不会是任何节点的祖先
  return hasChild(target.children, dragNode.id)
}

function hasChild(nodes: KnowledgeFileTreeNode[], id: number): boolean {
  for (const n of nodes) {
    if (n.id === id) return true
    if (n.children.length && hasChild(n.children, id)) return true
  }
  return false
}
```

### 8.3 错误处理

```ts
// drop 中调用 reorder 失败时：
// 1. 后端 500 错误（循环引用等）→ 提示用户 + 刷新树恢复正确状态
// 2. 网络错误 → 提示用户重试，保持原树不变
async function handleReorder(req: ReorderRequest) {
  try {
    await reorderFile(baseId, req)
    await loadTree() // 或乐观更新
  } catch (e: any) {
    if (e.response?.status === 500) {
      ElMessage.error(e.response.data?.message || '排序失败，请检查操作是否合法')
    } else {
      ElMessage.error('网络异常，排序失败')
    }
    await loadTree() // 恢复正确状态
  }
}
```

### 8.4 树刷新策略

拖拽操作后树结构发生变化，推荐 **直接刷新全树**（`loadTree()`），理由：
- 跨父级移动时，被拖拽节点的整个子树 ancestors 都会变化，局部更新复杂度高
- 树数据量通常不大（单知识库下数百节点），全量刷新开销可控
- 缓存失效后下一次 `GET /tree` 查询即返回最新结构

如果追求极致性能，可做乐观更新——前端本地修改 `treeData`，失败时回滚。

---
 
