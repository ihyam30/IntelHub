<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type DocumentChunk, type DocumentChunkPage, type DocumentItem, type KnowledgeBase, type Pipeline } from '../api'
import { useAuthStore } from '../stores/auth'
import { useRoute } from 'vue-router'

const bases = ref<KnowledgeBase[]>([])
const auth = useAuthStore()
const route = useRoute()
const routeIsAdmin = computed(() => route.path.startsWith('/admin/'))
const pipelines = ref<Pipeline[]>([])
const selected = ref('')
const selectedPipeline = ref('')
const documents = ref<DocumentItem[]>([])
const name = ref('')
const description = ref('')
const creating = ref(false)
const editDialogVisible = ref(false)
const editingBase = ref<KnowledgeBase | null>(null)
const editName = ref('')
const editDescription = ref('')
const savingBase = ref(false)
const chunkDialogVisible = ref(false)
const chunkDocument = ref<DocumentItem | null>(null)
const chunks = ref<DocumentChunk[]>([])
const chunksTotal = ref(0)
const chunksPage = ref(1)
const chunksPageSize = ref(20)
const chunksLoading = ref(false)
const editingChunkId = ref('')
const editingChunkContent = ref('')
const savingChunkId = ref('')
const uploadFile = ref<File | null>(null)
const uploading = ref(false)
const dragActive = ref(false)
const filter = ref('all')
const kbSearch = ref('')
const documentSearch = ref('')
const visibleBases = computed(() => {
  const term = kbSearch.value.trim().toLocaleLowerCase()
  return term ? bases.value.filter(base => `${base.name} ${base.description || ''}`.toLocaleLowerCase().includes(term)) : bases.value
})
const visibleDocuments = computed(() => documents.value.filter(doc => {
  const matchesStatus = filter.value === 'all' || doc.status.toLowerCase() === filter.value
  const matchesName = !documentSearch.value.trim() || doc.filename.toLocaleLowerCase().includes(documentSearch.value.trim().toLocaleLowerCase())
  return matchesStatus && matchesName
}))
const totalDocuments = computed(() => bases.value.reduce((sum, base) => sum + base.documents, 0))
const ownerCount = computed(() => new Set(bases.value.map(base => base.ownerId)).size)
const selectedBase = computed(() => bases.value.find(base => base.id === selected.value) || null)
const canUploadSelected = computed(() => !auth.isAdmin || selectedBase.value?.ownerId === auth.user?.id)

onMounted(() => { void Promise.all([loadBases(), loadPipelines()]) })

async function loadPipelines() {
  pipelines.value = await http.get('/ingestion-pipelines') as Pipeline[]
  if (!pipelines.value.some(item => item.id === selectedPipeline.value)) {
    selectedPipeline.value = pipelines.value.find(item => item.isDefault)?.id || pipelines.value[0]?.id || ''
  }
}

async function loadBases() {
  bases.value = await http.get('/knowledge-bases') as KnowledgeBase[]
  if (!selected.value || !bases.value.some(item => item.id === selected.value)) selected.value = bases.value[0]?.id || ''
  await loadDocuments()
}

async function loadDocuments() {
  if (!selected.value) { documents.value = []; return }
  documents.value = await http.get(`/knowledge-bases/${selected.value}/documents`) as DocumentItem[]
}

async function createKnowledgeBase() {
  if (!name.value.trim()) return
  creating.value = true
  try {
    await http.post('/knowledge-bases', { name: name.value.trim(), description: description.value.trim() })
    name.value = ''; description.value = ''
    await loadBases()
    ElMessage.success('知识库已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
  finally { creating.value = false }
}

function openEditBase(base: KnowledgeBase) {
  editingBase.value = base
  editName.value = base.name
  editDescription.value = base.description || ''
  editDialogVisible.value = true
}

function startCreateBase() {
  name.value = name.value || '新知识库'
  document.querySelector<HTMLInputElement>('.create-base-form input')?.focus()
}

async function updateKnowledgeBase() {
  const base = editingBase.value
  if (!base || !editName.value.trim()) return
  savingBase.value = true
  try {
    await http.put(`/knowledge-bases/${base.id}`, {
      name: editName.value.trim(),
      description: editDescription.value.trim(),
    })
    editDialogVisible.value = false
    await loadBases()
    ElMessage.success('知识库已更新')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新失败') }
  finally { savingBase.value = false }
}

function fileChanged(event: Event) {
  const input = event.target as HTMLInputElement
  setUploadFile(input.files?.[0] || null)
}

function setUploadFile(file: File | null) {
  if (!file) { uploadFile.value = null; return }
  uploadFile.value = null
  const extension = file.name.split('.').pop()?.toLocaleLowerCase() || ''
  if (!['pdf', 'doc', 'docx', 'md', 'txt'].includes(extension)) {
    ElMessage.warning('支持 PDF、DOC、DOCX、Markdown 和 TXT 文件')
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.warning('单个文件不能超过 50 MB')
    return
  }
  uploadFile.value = file
}

function handleDocumentDrop(event: DragEvent) {
  dragActive.value = false
  if (!canUploadSelected.value) return
  setUploadFile(event.dataTransfer?.files?.[0] || null)
}

async function upload() {
  if (!selected.value || !uploadFile.value) return
  uploading.value = true
  const body = new FormData()
  body.append('file', uploadFile.value)
  if (selectedPipeline.value) body.append('pipelineId', selectedPipeline.value)
  try {
    await http.post(`/knowledge-bases/${selected.value}/documents`, body, { headers: { 'Content-Type': 'multipart/form-data' } })
    uploadFile.value = null
    const input = document.querySelector<HTMLInputElement>('#document-file')
    if (input) input.value = ''
    await loadDocuments()
    ElMessage.success('文档已加入入库队列')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '上传失败') }
  finally { uploading.value = false }
}

async function removeDocument(doc: DocumentItem) {
  try { await ElMessageBox.confirm(`确定删除“${doc.filename}”及其索引吗？`, '删除文档', { type: 'warning' }) }
  catch { return }
  try {
    await http.delete(`/documents/${doc.id}`)
    await loadDocuments()
    ElMessage.success('文档及其索引已删除')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除失败，请重试') }
}

async function retryDocument(doc: DocumentItem) {
  try {
    await http.post(`/documents/${doc.id}/retry`)
    await loadDocuments()
    ElMessage.success('已重新加入入库队列')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '重试失败') }
}

async function openChunks(doc: DocumentItem) {
  chunkDocument.value = doc
  chunksPage.value = 1
  editingChunkId.value = ''
  chunkDialogVisible.value = true
  await loadChunks()
}

async function loadChunks() {
  const doc = chunkDocument.value
  if (!doc) return
  chunksLoading.value = true
  try {
    const result = await http.get(`/documents/${doc.id}/chunks`, {
      params: { page: chunksPage.value, pageSize: chunksPageSize.value },
    }) as DocumentChunkPage
    chunks.value = result.items
    chunksTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分块加载失败')
  } finally { chunksLoading.value = false }
}

function beginEditChunk(chunk: DocumentChunk) {
  editingChunkId.value = chunk.id
  editingChunkContent.value = chunk.content
}

function cancelEditChunk() {
  editingChunkId.value = ''
  editingChunkContent.value = ''
}

async function saveChunk(chunk: DocumentChunk) {
  const doc = chunkDocument.value
  const content = editingChunkContent.value.trim()
  if (!doc || !content) {
    ElMessage.warning('片段内容不能为空')
    return
  }
  savingChunkId.value = chunk.id
  try {
    const updated = await http.put(`/documents/${doc.id}/chunks/${chunk.id}`, { content }) as DocumentChunk
    chunks.value = chunks.value.map(item => item.id === updated.id ? updated : item)
    cancelEditChunk()
    ElMessage.success('片段已保存，向量和关键词索引已同步')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '片段保存失败') }
  finally { savingChunkId.value = '' }
}

async function removeBase(base: KnowledgeBase) {
  try { await ElMessageBox.confirm(`删除知识库“${base.name}”会同时移除文档、MinIO 原文件和检索索引，是否继续？`, '删除知识库', { type: 'warning' }) }
  catch { return }
  try {
    await http.delete(`/knowledge-bases/${base.id}`)
    selected.value = ''
    await loadBases()
    ElMessage.success('知识库及其文档资源已删除')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除失败，请重试') }
}

function statusLabel(status: string) {
  return ({ QUEUED: '排队中', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[status] || status
}

function statusType(status: string) {
  return ({ QUEUED: 'info', PROCESSING: 'warning', COMPLETED: 'success', FAILED: 'danger' } as Record<string, string>)[status] || 'info'
}

const refreshTimer = window.setInterval(() => {
  if (documents.value.some(doc => ['QUEUED', 'PROCESSING'].includes(doc.status))) void loadDocuments()
}, 4000)
window.addEventListener('beforeunload', () => window.clearInterval(refreshTimer), { once: true })
</script>

<template>
  <div class="page-content knowledge-page">
    <div class="page-title-row"><div><div class="eyebrow"><span></span> KNOWLEDGE LIBRARY</div><h1>{{ auth.isAdmin && routeIsAdmin ? '知识库管理' : '知识库' }}</h1><p>{{ auth.isAdmin && routeIsAdmin ? '管理知识空间、文档入库状态与切分片段。' : '整理、管理并索引您的文档，让知识随时可问。' }}</p></div><div class="title-actions"><el-button @click="loadBases">⟳ 刷新</el-button><el-button v-if="auth.isAdmin && routeIsAdmin" type="primary" @click="startCreateBase">＋ 新建知识库</el-button></div></div>
    <div class="knowledge-overview">
      <div class="overview-cell"><span class="overview-icon purple">▤</span><div><strong>{{ bases.length }}</strong><small>知识库</small></div><span class="overview-hint">全部</span></div>
      <div class="overview-cell"><span class="overview-icon cyan">◫</span><div><strong>{{ totalDocuments }}</strong><small>文档总数</small></div><span class="overview-hint">全部知识库</span></div>
      <div class="overview-cell"><span class="overview-icon green">✓</span><div><strong>{{ documents.filter(doc => doc.status === 'COMPLETED').length }}</strong><small>当前库已索引</small></div><span class="overview-hint">{{ documents.length }} 份文档</span></div>
      <div class="overview-cell"><span class="overview-icon amber">♙</span><div><strong>{{ ownerCount }}</strong><small>{{ auth.isAdmin ? '创建用户数' : '归属用户数' }}</small></div><span class="overview-hint">拥有知识库</span></div>
    </div>
    <div class="knowledge-layout">
      <aside class="knowledge-list-card">
        <div class="card-heading"><div><span class="section-kicker">YOUR LIBRARY</span><h2>我的知识库</h2></div><el-button class="tiny-add" circle @click="name = name || '新知识库'">＋</el-button></div>
        <el-input v-model="kbSearch" class="knowledge-search" placeholder="搜索知识库名称" clearable />
        <button v-for="base in visibleBases" :key="base.id" class="knowledge-list-item" :class="{ selected: selected === base.id }" @click="selected = base.id; loadDocuments()">
          <span class="kb-item-icon">▤</span><span class="kb-item-copy"><strong>{{ base.name }}</strong><small>{{ base.documents }} 份文档{{ auth.isAdmin ? ` · ${base.ownerId.slice(0, 8)}` : '' }}</small></span><span class="kb-item-arrow">›</span>
        </button>
        <div v-if="!visibleBases.length" class="knowledge-list-empty"><span>▤</span><p>{{ bases.length ? '没有匹配的知识库' : '还没有知识库' }}</p><small>{{ bases.length ? '试试其他搜索词' : '创建一个知识库来收纳文档' }}</small></div>
        <div class="create-base-form"><span class="section-kicker">创建知识库</span><el-input v-model="name" placeholder="知识库名称" maxlength="160" /><el-input v-model="description" type="textarea" :rows="2" placeholder="描述（可选）" maxlength="500" /><el-button type="primary" :loading="creating" :disabled="!name.trim()" @click="createKnowledgeBase">创建</el-button></div>
      </aside>
      <section class="documents-card">
        <div class="documents-header"><div><div class="section-kicker">DOCUMENTS</div><h2>{{ bases.find(item => item.id === selected)?.name || '选择知识库' }}</h2><p>支持 PDF、DOC/DOCX、Markdown 和 TXT，最大 50 MB</p></div><el-dropdown v-if="selected" trigger="click"><el-button>···</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="openEditBase(bases.find(item => item.id === selected)!)">编辑知识库</el-dropdown-item><el-dropdown-item divided @click="removeBase(bases.find(item => item.id === selected)!)">删除知识库</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
        <template v-if="selected && canUploadSelected">
          <div class="upload-flow-picker"><span>选择入库流程</span><el-select v-model="selectedPipeline" placeholder="使用默认流程"><el-option v-for="pipeline in pipelines" :key="pipeline.id" :label="pipeline.name" :value="pipeline.id" /></el-select></div>
          <div class="upload-zone" :class="{ 'has-file': uploadFile, 'is-dragging': dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="handleDocumentDrop"><div class="upload-symbol">↑</div><div class="upload-copy"><strong>{{ uploadFile ? uploadFile.name : dragActive ? '松开鼠标添加文档' : '拖入文档，或浏览本地文件' }}</strong><span>{{ uploadFile ? `${(uploadFile.size / 1024 / 1024).toFixed(2)} MB · 点击开始入库` : '支持 PDF、DOC/DOCX、Markdown 和 TXT，最大 50 MB' }}</span></div><label class="browse-button" for="document-file">浏览文件</label><input id="document-file" type="file" accept=".pdf,.doc,.docx,.md,.txt" hidden @change="fileChanged" /><el-button type="primary" :disabled="!uploadFile" :loading="uploading" @click="upload">开始入库 <span>→</span></el-button></div>
        </template>
        <div v-else class="select-kb-note">{{ selected ? '此知识库归属其他用户。管理员可以管理文档与片段；上传请切换到自己创建的知识库。' : '先创建或选择一个知识库。' }}</div>
        <div class="table-toolbar"><div class="table-title"><h3>文档列表</h3><span>{{ documents.length }}</span></div><div class="table-filters"><el-input v-model="documentSearch" class="document-search" placeholder="搜索文档名称" clearable /><button v-for="item in [{id:'all',label:'全部'},{id:'completed',label:'已索引'},{id:'processing',label:'处理中'},{id:'failed',label:'失败'}]" :key="item.id" :class="{ active: filter === item.id }" @click="filter = item.id">{{ item.label }}</button><button class="icon-only" title="刷新" @click="loadDocuments">⟳</button></div></div>
        <div v-if="!visibleDocuments.length" class="document-empty"><div class="empty-doc-icon">▤</div><strong>{{ documents.length ? '没有符合条件的文档' : '这里还没有文档' }}</strong><span>{{ documents.length ? '尝试切换筛选条件。' : '上传第一份文档，开始建立知识索引。' }}</span></div>
        <div v-else class="document-table-wrap"><table class="document-table"><thead><tr><th>文档名称</th><th>状态</th><th>文本片段</th><th>上传时间</th><th></th></tr></thead><tbody><tr v-for="doc in visibleDocuments" :key="doc.id"><td><div class="file-cell"><span class="file-type" :class="doc.filename.split('.').pop()?.toLowerCase()">{{ doc.filename.split('.').pop()?.toUpperCase() }}</span><div><strong>{{ doc.filename }}</strong><small v-if="doc.error" class="error-text">{{ doc.error }}</small></div></div></td><td><el-tag :type="statusType(doc.status) as any" effect="light" round>{{ statusLabel(doc.status) }}</el-tag></td><td>{{ doc.chunkCount || '—' }}</td><td>{{ new Date(doc.createdAt).toLocaleDateString('zh-CN') }}</td><td><el-dropdown trigger="click"><button class="row-more">···</button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="doc.status === 'COMPLETED'" @click="openChunks(doc)">查看 / 编辑片段</el-dropdown-item><el-dropdown-item v-if="doc.status === 'FAILED'" @click="retryDocument(doc)">重试入库</el-dropdown-item><el-dropdown-item @click="removeDocument(doc)">删除文档</el-dropdown-item></el-dropdown-menu></template></el-dropdown></td></tr></tbody></table></div>
        <div class="documents-foot"><span>⟲ 入库节点和处理结果可在「入库流程」中查看</span><RouterLink to="/pipelines">查看流程 <b>→</b></RouterLink></div>
      </section>
    </div>
    <el-dialog v-model="editDialogVisible" title="编辑知识库" width="480px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="知识库名称" required><el-input v-model="editName" maxlength="160" show-word-limit /></el-form-item>
        <el-form-item label="描述"><el-input v-model="editDescription" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editDialogVisible = false">取消</el-button><el-button type="primary" :loading="savingBase" :disabled="!editName.trim()" @click="updateKnowledgeBase">保存修改</el-button></template>
    </el-dialog>
    <el-dialog v-model="chunkDialogVisible" :title="`文档片段 · ${chunkDocument?.filename || ''}`" width="min(900px, 92vw)" top="5vh" :close-on-click-modal="false" class="chunk-dialog">
      <div class="chunk-dialog-intro"><span>共 {{ chunksTotal }} 个片段</span><span>编辑后会重新生成向量并同步关键词索引；重新运行入库流程会重新生成片段。</span></div>
      <div v-loading="chunksLoading" class="chunk-list">
        <article v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
          <header class="chunk-card-header">
            <div class="chunk-card-title"><strong>片段 {{ chunk.chunkIndex + 1 }}</strong><el-tag v-if="chunk.pageNumber" size="small" effect="plain">第 {{ chunk.pageNumber }} 页</el-tag><el-tag v-if="chunk.manuallyEdited" type="warning" size="small" effect="plain">已编辑</el-tag></div>
            <div v-if="editingChunkId !== chunk.id" class="chunk-card-actions"><small>{{ chunk.updatedAt ? new Date(chunk.updatedAt).toLocaleString('zh-CN') : '' }}</small><el-button link type="primary" @click="beginEditChunk(chunk)">编辑</el-button></div>
          </header>
          <el-input v-if="editingChunkId === chunk.id" v-model="editingChunkContent" type="textarea" :autosize="{ minRows: 5, maxRows: 14 }" maxlength="20000" show-word-limit />
          <pre v-else class="chunk-content">{{ chunk.content }}</pre>
          <div v-if="editingChunkId === chunk.id" class="chunk-edit-actions"><el-button @click="cancelEditChunk">取消</el-button><el-button type="primary" :loading="savingChunkId === chunk.id" :disabled="!editingChunkContent.trim()" @click="saveChunk(chunk)">保存片段</el-button></div>
        </article>
        <el-empty v-if="!chunksLoading && !chunks.length" description="该文档暂无可显示的片段" />
      </div>
      <template #footer><el-pagination v-if="chunksTotal > chunksPageSize" v-model:current-page="chunksPage" v-model:page-size="chunksPageSize" :page-sizes="[10, 20, 50]" :total="chunksTotal" layout="total, sizes, prev, pager, next" @current-change="loadChunks" @size-change="chunksPage = 1; loadChunks()" /></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chunk-dialog-intro { display: flex; justify-content: space-between; gap: 16px; margin: -8px 0 16px; color: var(--el-text-color-secondary); font-size: 13px; }
.chunk-list { min-height: 160px; max-height: 65vh; overflow: auto; padding: 2px 4px 2px 0; }
.chunk-card { margin-bottom: 14px; padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-bg-color); }
.chunk-card-header, .chunk-card-title, .chunk-card-actions, .chunk-edit-actions { display: flex; align-items: center; }
.chunk-card-header { justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.chunk-card-title, .chunk-card-actions { gap: 10px; }
.chunk-card-title strong { color: var(--el-text-color-primary); }
.chunk-card-actions small { color: var(--el-text-color-secondary); }
.chunk-content { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--el-text-color-regular); font: inherit; font-size: 13px; line-height: 1.75; }
.chunk-edit-actions { justify-content: flex-end; gap: 8px; margin-top: 12px; }
@media (max-width: 640px) { .chunk-dialog-intro { flex-direction: column; gap: 6px; } .chunk-card-header { align-items: flex-start; flex-direction: column; } }
</style>
