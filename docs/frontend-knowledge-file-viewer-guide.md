# 知识库多格式文件查看——前端开发指南

## 1. 后端接口变更汇总

### 新增接口

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| POST | `/knowledge-file/upload` | file (Multipart), baseId, parentId? | 上传文件到 MinIO |
| GET | `/knowledge-file/{id}/url` | — | 获取文件 MinIO 预签名 URL |
| GET | `/knowledge-file/{id}/download` | — | 返回下载 URL + Content-Disposition 头 |

### 已有接口响应变更

`GET /knowledge-file/tree` 和 `GET /knowledge-file/{id}` 返回的数据新增字段：

```json
{
  "id": 123,
  "name": "需求文档",
  "type": "file",
  "fileType": "application/pdf",
  "content": null,
  "storagePath": "knowledge/1/uuid.pdf",
  "fileSize": 2048576,
  "fileUrl": "http://minio/dear-agent/knowledge/1/uuid.pdf?...",
  "children": []
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | string \| null | 文本内容，纯 md/txt 文件有值，二进制文件为 null |
| `storagePath` | string \| null | MinIO key，有值表示文件在 MinIO |
| `fileSize` | number \| null | 文件字节数 |
| `fileUrl` | string \| null | 预签名访问 URL，可直接用于 src/href |

---

## 2. 前端按文件类型渲染策略

核心逻辑：**根据 `fileType` 字段（即 mineType）决定渲染方式**。

```typescript
// 类型判断工具
function getViewerMode(fileType: string): 'text-editor' | 'pdf-viewer' | 'image-viewer' | 'office-preview' | 'download-only' {
  if (!fileType) return 'download-only';

  if (fileType.startsWith('text/')) {
    return 'text-editor';      // text/markdown, text/plain, text/html, text/css, text/yaml ...
  }
  if (fileType === 'application/pdf') {
    return 'pdf-viewer';
  }
  if (fileType.startsWith('image/')) {
    return 'image-viewer';
  }
  if (fileType.includes('officedocument') || fileType === 'application/msword' || fileType === 'application/vnd.ms-excel' || fileType === 'application/vnd.ms-powerpoint') {
    return 'office-preview';
  }
  return 'download-only';
}
```

### 2.1 Markdown / 文本类（text/*）

**特征**：`fileType` 为 `text/markdown`、`text/plain`、`text/html` 等，`content` 字段有值。

直接使用现有的 Markdown 编辑器和文本编辑器渲染 `content`，无需额外请求。

```vue
<!-- 伪代码示意 -->
<template v-if="mode === 'text-editor'">
  <MdEditor v-if="file.fileType === 'text/markdown'" v-model="file.content" />
  <CodeEditor v-else v-model="file.content" :language="getLanguage(file.fileType)" />
</template>
```

### 2.2 PDF 类（application/pdf）

**特征**：`fileType === 'application/pdf'`，`content` 为 null，使用 `fileUrl` 加载。

**推荐方案一：iframe 内嵌**（最简单）

```html
<iframe
  :src="file.fileUrl"
  width="100%"
  height="100%"
  style="min-height: 600px; border: none;"
/>
```

**推荐方案二：pdf.js**（功能丰富）

```bash
npm install pdfjs-dist
```

```typescript
import * as pdfjsLib from 'pdfjs-dist';

pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.js';

async function renderPdf(url: string, container: HTMLElement) {
  const pdf = await pdfjsLib.getDocument(url).promise;
  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i);
    const viewport = page.getViewport({ scale: 1.5 });
    const canvas = document.createElement('canvas');
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    container.appendChild(canvas);
    await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;
  }
}
```

**推荐方案三：`<object>` 标签**

```html
<object :data="file.fileUrl" type="application/pdf" width="100%" height="100%">
  <p>浏览器不支持 PDF 预览，<a :href="file.fileUrl" download>点击下载</a></p>
</object>
```

### 2.3 图片类（image/*）

直接使用 `fileUrl` 作为图片 src，配合点击放大（lightbox）。

```vue
<template v-if="mode === 'image-viewer'">
  <div class="image-viewer" @click="openLightbox">
    <img :src="file.fileUrl" :alt="file.name" loading="lazy" />
  </div>
</template>
```

### 2.4 Office 文档类（docx/xlsx/pptx）

**推荐方案：Microsoft Office Online Viewer**（免费，无需后端）

```typescript
function getOfficePreviewUrl(storageUrl: string): string {
  const encoded = encodeURIComponent(storageUrl);
  return `https://view.officeapps.live.com/op/embed.aspx?src=${encoded}`;
}
```

```html
<iframe
  :src="getOfficePreviewUrl(file.fileUrl)"
  width="100%"
  height="600px"
  style="border: none;"
/>
```

> **注意**：此方案要求文件 URL 能被公网访问（即 MinIO 预签名 URL 对外可达）。内网环境需要通过后端代理返回文件流。

**备选方案（内网环境）**：后端新增 `/knowledge-file/{id}/stream` 端点返回文件流，前端配合 viewer 库如 `@vue-office/excel`、`@vue-office/docx`。

### 2.5 其他类型

下载按钮，触发浏览器另存为：

```typescript
async function downloadFile(file: KnowledgeFile) {
  const res = await fetch(`/knowledge-file/${file.id}/download`);
  const data = await res.json();
  // data.data 是文件 URL
  window.open(data.data, '_blank');
}
```

---

## 3. 文件上传组件

```vue
<template>
  <div class="file-upload">
    <input
      ref="fileInput"
      type="file"
      @change="handleUpload"
      :accept="acceptTypes"
      style="display:none"
    />
    <button @click="$refs.fileInput.click()">选择文件上传</button>
    <p>支持格式：PDF, Word, Excel, PPT, 图片, 文本文件等</p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import axios from 'axios';

const props = defineProps<{ baseId: number; parentId?: number }>();
const emit = defineEmits<{ uploadSuccess: (file: any) => void }>();

const acceptTypes = [
  '.md', '.txt', '.pdf',
  '.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg',
  '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.csv', '.json', '.xml', '.html', '.css', '.js',
  '.java', '.py', '.yaml', '.yml',
].join(',');

const uploading = ref(false);
const progress = ref(0);

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;

  const formData = new FormData();
  formData.append('file', file);
  formData.append('baseId', String(props.baseId));
  if (props.parentId) {
    formData.append('parentId', String(props.parentId));
  }

  uploading.value = true;
  try {
    const res = await axios.post('/knowledge-file/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        progress.value = e.total ? Math.round((e.loaded * 100) / e.total) : 0;
      },
    });
    emit('uploadSuccess', res.data.data);
  } finally {
    uploading.value = false;
    input.value = ''; // 允许重复上传同一文件
  }
}
</script>
```

---

## 4. 文件查看总组件

```vue
<template>
  <div class="knowledge-file-viewer">
    <!-- 文本类 - 现有编辑器 -->
    <MdEditor
      v-if="mode === 'text-editor' && file.fileType === 'text/markdown'"
      v-model="file.content"
      :readonly="true"
    />

    <!-- PDF -->
    <iframe
      v-else-if="mode === 'pdf-viewer'"
      :src="file.fileUrl"
      class="viewer-iframe"
    />

    <!-- 图片 -->
    <div v-else-if="mode === 'image-viewer'" class="image-viewer">
      <img :src="file.fileUrl" :alt="file.name" @click="lightbox" />
    </div>

    <!-- Office 文档 - 微软在线预览 -->
    <iframe
      v-else-if="mode === 'office-preview'"
      :src="officePreviewUrl"
      class="viewer-iframe"
    />

    <!-- 其他 - 下载 -->
    <div v-else class="download-only">
      <p>该文件类型暂不支持在线预览</p>
      <button @click="download">下载文件 ({{ formatSize(file.fileSize) }})</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { KnowledgeFile } from '@/types';

const props = defineProps<{ file: KnowledgeFile }>();

const mode = computed(() => getViewerMode(props.file.fileType));

const officePreviewUrl = computed(() => {
  if (!props.file.fileUrl) return '';
  return `https://view.officeapps.live.com/op/embed.aspx?src=${encodeURIComponent(props.file.fileUrl)}`;
});

function formatSize(bytes: number | null): string {
  if (!bytes) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1048576).toFixed(1) + ' MB';
}
</script>

<style scoped>
.viewer-iframe {
  width: 100%;
  min-height: 600px;
  border: none;
}
.image-viewer img {
  max-width: 100%;
  cursor: pointer;
  border-radius: 4px;
}
.download-only {
  text-align: center;
  padding: 40px;
}
</style>
```

---

## 5. TypeScript 类型定义

```typescript
interface KnowledgeFileTreeNode {
  id: number;
  name: string;
  type: 'folder' | 'file';       // folder = 目录节点
  fileType: string | null;       // mineType, e.g. "application/pdf"
  content: string | null;        // 文本内容（仅文本类文件）
  storagePath: string | null;    // MinIO key
  fileSize: number | null;       // 文件大小（字节）
  fileUrl: string | null;        // 预签名访问 URL
  createTime: string;
  updateTime: string;
  children: KnowledgeFileTreeNode[];
}
```

---

## 6. 树组件文件图标建议

根据 `fileType` 展示不同的文件图标：

```typescript
function getFileIcon(fileType: string | null): string {
  if (!fileType) return 'file';
  if (fileType.startsWith('text/markdown')) return 'file-text';
  if (fileType.startsWith('text/')) return 'file-code';
  if (fileType === 'application/pdf') return 'file-pdf';
  if (fileType.startsWith('image/')) return 'file-image';
  if (fileType.includes('word')) return 'file-word';
  if (fileType.includes('excel') || fileType.includes('csv')) return 'file-excel';
  if (fileType.includes('powerpoint')) return 'file-ppt';
  return 'file-generic';
}
```

---

## 7. 注意事项

1. **预签名 URL 有有效期**：MinIO 默认 7 天过期，前端每次获取文件详情时后端会重新生成，正常使用没问题。但如果缓存了 `fileUrl` 超过有效期，需重新请求 `GET /knowledge-file/{id}`。

2. **Office 在线预览要求公网可达**：`view.officeapps.live.com` 需要访问 MinIO 文件 URL。内网部署时，可将 `/download` 接口改为 302 跳转到预签名 URL，或新增一个流式代理端点。

3. **大文件上传**：当前 Spring Multipart 限制 50MB，超大视频/音频后续考虑分片上传。

4. **md/txt 文件编辑**：`POST /knowledge-file` 和 `PUT /knowledge-file/{id}` 仍通过 JSON body 提交 `content`。上传 md/txt 文件时，文件不存储在 MinIO（storagePath 为空），内容存入 DB。

5. **缓存一致性**：上传/删除文件后，`tree` 和 `id` 缓存自动失效。
