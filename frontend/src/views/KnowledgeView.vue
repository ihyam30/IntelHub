<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type DocumentItem, type KnowledgeBase, type Pipeline } from '../api'

const bases = ref<KnowledgeBase[]>([])
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
const uploadFile = ref<File | null>(null)
const uploading = ref(false)
const filter = ref('all')
const visibleDocuments = computed(() => documents.value.filter(doc => filter.value === 'all' || doc.status.toLowerCase() === filter.value))

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
  uploadFile.value = input.files?.[0] || null
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
    <div class="page-title-row"><div><div class="eyebrow"><span></span> KNOWLEDGE LIBRARY</div><h1>知识库</h1><p>整理、管理并索引您的文档，让知识随时可问。</p></div><div class="title-actions"><el-button @click="loadBases">⟳ 刷新</el-button></div></div>
    <div class="knowledge-overview">
      <div class="overview-cell"><span class="overview-icon purple">▤</span><div><strong>{{ bases.length }}</strong><small>知识库</small></div></div>
      <div class="overview-cell"><span class="overview-icon cyan">◫</span><div><strong>{{ documents.length }}</strong><small>当前库文档</small></div></div>
      <div class="overview-cell"><span class="overview-icon green">✓</span><div><strong>{{ documents.filter(doc => doc.status === 'COMPLETED').length }}</strong><small>已索引</small></div></div>
      <div class="overview-hint"><span>✦</span> 文档完成索引后即可用于智能问答。</div>
    </div>
    <div class="knowledge-layout">
      <aside class="knowledge-list-card">
        <div class="card-heading"><div><span class="section-kicker">YOUR LIBRARY</span><h2>我的知识库</h2></div><el-button class="tiny-add" circle @click="name = name || '新知识库'">＋</el-button></div>
        <button v-for="base in bases" :key="base.id" class="knowledge-list-item" :class="{ selected: selected === base.id }" @click="selected = base.id; loadDocuments()">
          <span class="kb-item-icon">▤</span><span class="kb-item-copy"><strong>{{ base.name }}</strong><small>{{ base.documents }} 份文档</small></span><span class="kb-item-arrow">›</span>
        </button>
        <div v-if="!bases.length" class="knowledge-list-empty"><span>▤</span><p>还没有知识库</p><small>创建一个知识库来收纳文档</small></div>
        <div class="create-base-form"><span class="section-kicker">创建知识库</span><el-input v-model="name" placeholder="知识库名称" maxlength="160" /><el-input v-model="description" type="textarea" :rows="2" placeholder="描述（可选）" maxlength="500" /><el-button type="primary" :loading="creating" :disabled="!name.trim()" @click="createKnowledgeBase">创建</el-button></div>
      </aside>
      <section class="documents-card">
        <div class="documents-header"><div><div class="section-kicker">DOCUMENTS</div><h2>{{ bases.find(item => item.id === selected)?.name || '选择知识库' }}</h2><p>支持 PDF、DOC/DOCX、Markdown 和 TXT，最大 50 MB</p></div><el-dropdown v-if="selected" trigger="click"><el-button>···</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="openEditBase(bases.find(item => item.id === selected)!)">编辑知识库</el-dropdown-item><el-dropdown-item divided @click="removeBase(bases.find(item => item.id === selected)!)">删除知识库</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
        <div v-if="selected" class="upload-flow-picker" style="display:flex;align-items:center;gap:12px;margin-bottom:14px"><span>选择入库流程</span><el-select v-model="selectedPipeline" style="width:260px" placeholder="使用默认流程"><el-option v-for="pipeline in pipelines" :key="pipeline.id" :label="pipeline.name" :value="pipeline.id" /></el-select></div>
        <div v-if="selected" class="upload-zone" :class="{ 'has-file': uploadFile }"><div class="upload-symbol">↑</div><div class="upload-copy"><strong>{{ uploadFile ? uploadFile.name : '拖入文档，或浏览本地文件' }}</strong><span>{{ uploadFile ? `${(uploadFile.size / 1024 / 1024).toFixed(2)} MB · 点击开始入库` : '解析、切分和索引将在后台自动执行' }}</span></div><label class="browse-button" for="document-file">浏览文件</label><input id="document-file" type="file" accept=".pdf,.doc,.docx,.md,.txt" hidden @change="fileChanged" /><el-button type="primary" :disabled="!uploadFile" :loading="uploading" @click="upload">开始入库 <span>→</span></el-button></div>
        <div v-else class="select-kb-note">先创建或选择一个知识库。</div>
        <div class="table-toolbar"><div class="table-title"><h3>文档列表</h3><span>{{ documents.length }}</span></div><div class="table-filters"><button v-for="item in [{id:'all',label:'全部'},{id:'completed',label:'已索引'},{id:'processing',label:'处理中'},{id:'failed',label:'失败'}]" :key="item.id" :class="{ active: filter === item.id }" @click="filter = item.id">{{ item.label }}</button><button class="icon-only" title="刷新" @click="loadDocuments">⟳</button></div></div>
        <div v-if="!visibleDocuments.length" class="document-empty"><div class="empty-doc-icon">▤</div><strong>{{ documents.length ? '没有符合条件的文档' : '这里还没有文档' }}</strong><span>{{ documents.length ? '尝试切换筛选条件。' : '上传第一份文档，开始建立知识索引。' }}</span></div>
        <div v-else class="document-table-wrap"><table class="document-table"><thead><tr><th>文档名称</th><th>状态</th><th>文本片段</th><th>上传时间</th><th></th></tr></thead><tbody><tr v-for="doc in visibleDocuments" :key="doc.id"><td><div class="file-cell"><span class="file-type" :class="doc.filename.split('.').pop()?.toLowerCase()">{{ doc.filename.split('.').pop()?.toUpperCase() }}</span><div><strong>{{ doc.filename }}</strong><small v-if="doc.error" class="error-text">{{ doc.error }}</small></div></div></td><td><el-tag :type="statusType(doc.status) as any" effect="light" round>{{ statusLabel(doc.status) }}</el-tag></td><td>{{ doc.chunkCount || '—' }}</td><td>{{ new Date(doc.createdAt).toLocaleDateString('zh-CN') }}</td><td><el-dropdown trigger="click"><button class="row-more">···</button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="doc.status === 'FAILED'" @click="retryDocument(doc)">重试入库</el-dropdown-item><el-dropdown-item @click="removeDocument(doc)">删除文档</el-dropdown-item></el-dropdown-menu></template></el-dropdown></td></tr></tbody></table></div>
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
  </div>
</template>
