<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type Pipeline } from '../api'
import { useRoute } from 'vue-router'

const route = useRoute()
const adminRoute = computed(() => route.path.startsWith('/admin/'))
const DEFAULT_CHUNK_SIZE = 512
const DEFAULT_OVERLAP_SIZE = 128
const MAX_CHUNK_SIZE = 10000
const pipelines = ref<Pipeline[]>([])
const selected = ref<Pipeline | null>(null)
const name = ref('')
const saving = ref(false)
const runs = ref<any[]>([])
const nodeToAdd = ref('')
const nodeMeta = [
  { type: 'parse', title: '解析文档', detail: '识别文档格式并提取正文', icon: '▤', tone: 'violet' },
  { type: 'normalize', title: '文本规范化', detail: '清理空白、换行和不可见字符', icon: '≋', tone: 'blue' },
  { type: 'chunk', title: '文本切分', detail: '按段落与长度生成文本片段', icon: '⌘', tone: 'blue' },
  { type: 'metadata', title: '元数据增强', detail: '补充来源、文件名与处理时间', icon: '◈', tone: 'violet' },
  { type: 'embedding', title: '文本向量化', detail: '为每个文本片段生成 Embedding 向量', icon: '∑', tone: 'blue' },
  { type: 'index', title: '写入索引', detail: '同步向量索引和关键词索引', icon: '✧', tone: 'mint' },
]
const optionalNodeTypes = new Set(['normalize', 'metadata'])
const editable = computed(() => !!selected.value && !selected.value.isDefault)
const canEditChunkSettings = computed(() => editable.value || (adminRoute.value && !!selected.value?.isDefault))
const addableNodes = computed(() => nodeMeta.filter(node => optionalNodeTypes.has(node.type)
  && !selected.value?.nodes.some(step => step.type === node.type)))
const canSave = computed(() => canEditChunkSettings.value && !!selected.value
  && isValidPipeline(selected.value.nodes) && hasValidChunkSettings(selected.value.nodes))

onMounted(() => { void load() })

async function load() {
  const result = await http.get('/ingestion-pipelines') as Pipeline[]
  pipelines.value = result.map(normalizePipeline)
  selected.value = pipelines.value.find(item => item.id === selected.value?.id) || pipelines.value[0] || null
  if (selected.value) await loadRuns()
}

function normalizePipeline(pipeline: Pipeline): Pipeline {
  return {
    ...pipeline,
    nodes: pipeline.nodes.map(step => step.type === 'chunk'
      ? { ...step, settings: {
          chunkSize: step.settings?.chunkSize ?? DEFAULT_CHUNK_SIZE,
          overlapSize: Math.min(step.settings?.overlapSize ?? DEFAULT_OVERLAP_SIZE,
            Math.floor((step.settings?.chunkSize ?? DEFAULT_CHUNK_SIZE) / 2)),
        } }
      : step),
  }
}

function setChunkSetting(step: Pipeline['nodes'][number], key: 'chunkSize' | 'overlapSize', value: number | undefined) {
  if (value == null) return
  const settings = step.settings || { chunkSize: DEFAULT_CHUNK_SIZE, overlapSize: DEFAULT_OVERLAP_SIZE }
  const updated = { ...settings, [key]: Math.trunc(value) }
  const maxOverlapSize = Math.floor(updated.chunkSize / 2)
  if (updated.overlapSize > maxOverlapSize) {
    updated.overlapSize = maxOverlapSize
  }
  step.settings = updated
}

function hasValidChunkSettings(nodes: Pipeline['nodes']) {
  const settings = nodes.find(step => step.type === 'chunk')?.settings
  return !!settings
    && Number.isInteger(settings.chunkSize) && settings.chunkSize >= 1 && settings.chunkSize <= MAX_CHUNK_SIZE
    && Number.isInteger(settings.overlapSize) && settings.overlapSize >= 0
    && settings.overlapSize <= Math.floor(settings.chunkSize / 2)
}

async function loadRuns() {
  if (!selected.value) return
  runs.value = await http.get(`/ingestion-runs?pipelineId=${encodeURIComponent(selected.value.id)}&limit=10`).catch(() => []) as any[]
}

function move(index: number, direction: -1 | 1) {
  if (!selected.value) return
  const items = [...selected.value.nodes]
  const next = index + direction
  if (next < 0 || next >= items.length) return
  ;[items[index], items[next]] = [items[next], items[index]]
  if (!isValidPipeline(items)) {
    ElMessage.warning('流程必须以解析开始、索引结束，并先切分再生成向量。')
    return
  }
  selected.value = { ...selected.value, nodes: items }
}

function isValidPipeline(nodes: Pipeline['nodes']) {
  const types = nodes.map(step => step.type)
  return types.length >= 4 && types[0] === 'parse' && types.at(-1) === 'index'
    && types.includes('chunk') && types.includes('embedding')
    && types.indexOf('chunk') < types.indexOf('embedding')
    && (!types.includes('normalize') || types.indexOf('normalize') < types.indexOf('chunk'))
    && new Set(types).size === types.length
}

function addNode() {
  if (!selected.value || !nodeToAdd.value || !optionalNodeTypes.has(nodeToAdd.value)) return
  const items = [...selected.value.nodes]
  if (items.some(step => step.type === nodeToAdd.value)) return
  const beforeType = nodeToAdd.value === 'normalize' ? 'chunk' : 'embedding'
  const insertAt = items.findIndex(step => step.type === beforeType)
  items.splice(insertAt < 0 ? items.length - 1 : insertAt, 0, { type: nodeToAdd.value })
  selected.value = { ...selected.value, nodes: items }
  nodeToAdd.value = ''
}

function removeNode(index: number) {
  if (!selected.value || !optionalNodeTypes.has(selected.value.nodes[index]?.type)) return
  const items = [...selected.value.nodes]
  items.splice(index, 1)
  selected.value = { ...selected.value, nodes: items }
}

async function save() {
  if (!selected.value || !canSave.value) return
  saving.value = true
  try {
    await http.put(`/ingestion-pipelines/${selected.value.id}`, { name: selected.value.name, nodes: selected.value.nodes })
    ElMessage.success('入库流程已保存')
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { saving.value = false }
}

async function create() {
  const result = await ElMessageBox.prompt('给新的入库流程起个名字', '创建流程', { inputValue: name.value || '我的入库流程', inputPattern: /.+/, inputErrorMessage: '名称不能为空' }).catch(() => null)
  if (!result) return
  try {
    const item = await http.post('/ingestion-pipelines', { name: result.value, nodes: [{ type: 'parse' }, { type: 'normalize' }, { type: 'chunk', settings: { chunkSize: DEFAULT_CHUNK_SIZE, overlapSize: DEFAULT_OVERLAP_SIZE } }, { type: 'metadata' }, { type: 'embedding' }, { type: 'index' }] }) as Pipeline
    await load()
    selected.value = pipelines.value.find(pipeline => pipeline.id === item.id) || normalizePipeline(item)
    ElMessage.success('流程已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
}

async function remove() {
  if (!selected.value || !editable.value) return
  await ElMessageBox.confirm(`确定删除流程「${selected.value.name}」吗？`, '删除流程', { type: 'warning' })
  await http.delete(`/ingestion-pipelines/${selected.value.id}`)
  selected.value = null
  await load()
}
</script>

<template>
  <div class="page-content pipeline-page">
    <div class="page-title-row"><div><div class="eyebrow"><span></span> INGESTION STUDIO</div><h1>{{ adminRoute ? '流水线管理' : '入库流程' }}</h1><p>组合可选节点、调整执行顺序，并追踪每次入库运行。</p></div><div class="title-actions"><RouterLink v-if="adminRoute" class="console-link-button" to="/admin/tasks">▣ 任务记录</RouterLink><el-button type="primary" @click="create">＋ 新建流程</el-button></div></div>
    <div class="pipeline-layout">
      <aside class="pipeline-list-card"><div class="card-heading"><div><span class="section-kicker">PIPELINE LIBRARY</span><h2>流程列表</h2></div><span class="count-pill">{{ pipelines.length }}</span></div><button v-for="pipeline in pipelines" :key="pipeline.id" class="pipeline-list-item" :class="{ active: selected?.id === pipeline.id }" @click="selected = pipeline; loadRuns()"><span class="pipeline-list-icon">⌘</span><span><strong>{{ pipeline.name }}</strong><small>{{ pipeline.nodes.length }} 个处理节点</small></span><el-tag v-if="pipeline.isDefault" size="small" effect="plain">默认</el-tag></button><div class="pipeline-side-note"><span>✦</span> 默认流程可供所有知识库文档使用。您也可以创建自己的流程。</div></aside>
      <section v-if="selected" class="pipeline-editor-card"><div class="pipeline-editor-head"><div><div class="section-kicker">PIPELINE DESIGNER</div><div class="pipeline-name-row"><h2>{{ selected.name }}</h2><el-tag v-if="selected.isDefault" type="info" effect="plain">系统默认</el-tag><el-input v-else v-model="selected.name" class="pipeline-name-input" maxlength="160" /></div><p>节点按从上到下的顺序执行。失败任务可在知识库文档列表重试。</p></div><div class="pipeline-actions"><el-button v-if="editable" text type="danger" @click="remove">删除</el-button><el-button v-if="canEditChunkSettings" type="primary" :loading="saving" :disabled="!canSave" @click="save">保存{{ selected.isDefault ? '切分参数' : '流程' }}</el-button></div></div>
        <div class="pipeline-canvas"><template v-for="(step, index) in selected.nodes" :key="step.type"><div class="pipeline-node"><div class="pipeline-node-icon" :class="nodeMeta.find(node => node.type === step.type)?.tone">{{ nodeMeta.find(node => node.type === step.type)?.icon || '◇' }}</div><div class="pipeline-node-copy"><div class="pipeline-node-title"><span class="node-step">{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ nodeMeta.find(node => node.type === step.type)?.title || step.type }}</strong><el-tag size="small" type="success" effect="light">已启用</el-tag></div><p>{{ nodeMeta.find(node => node.type === step.type)?.detail }}</p><div v-if="step.type === 'chunk'" class="chunk-settings"><label><span>切分长度（字符）</span><el-input-number :model-value="step.settings?.chunkSize ?? DEFAULT_CHUNK_SIZE" :min="1" :max="MAX_CHUNK_SIZE" :disabled="!canEditChunkSettings" @update:model-value="setChunkSetting(step, 'chunkSize', $event)" /></label><label><span>重叠长度（字符）</span><el-input-number :model-value="step.settings?.overlapSize ?? DEFAULT_OVERLAP_SIZE" :min="0" :max="Math.floor((step.settings?.chunkSize ?? DEFAULT_CHUNK_SIZE) / 2)" :disabled="!canEditChunkSettings" @update:model-value="setChunkSetting(step, 'overlapSize', $event)" /></label><small>按段落边界优先切分；重叠长度最多为切分长度的 50%。</small></div></div><div class="node-controls"><button :disabled="!editable || index === 0" title="上移" @click="move(index, -1)">↑</button><button :disabled="!editable || index === selected!.nodes.length - 1" title="下移" @click="move(index, 1)">↓</button><button v-if="editable && optionalNodeTypes.has(step.type)" title="移除可选节点" @click="removeNode(index)">×</button></div></div><div v-if="index < selected.nodes.length - 1" class="pipeline-connector"><span></span><i>↓</i><span></span></div></template><div class="pipeline-end"><span>✓</span> 文档进入知识索引</div></div>
        <div class="pipeline-info-row"><div><span class="info-icon">ⓘ</span><span>切分参数以字符计；重叠长度最多为切分长度的 50%。默认流程仅管理员可改参数，自定义流程由创建者编辑。</span></div><div v-if="editable" style="display:flex;align-items:center;gap:8px"><el-select v-model="nodeToAdd" size="small" placeholder="添加可选节点" style="width:160px"><el-option v-for="node in addableNodes" :key="node.type" :label="node.title" :value="node.type" /></el-select><el-button size="small" :disabled="!nodeToAdd" @click="addNode">添加节点</el-button></div><span v-else-if="!canEditChunkSettings">系统默认流程的切分参数只读</span></div>
        <div class="runs-section"><div class="runs-heading"><div><div class="section-kicker">RECENT EXECUTION</div><h3>最近执行</h3></div><el-button text @click="loadRuns">刷新</el-button></div><div v-if="!runs.length" class="runs-empty">暂无执行记录。文档上传后，Pipeline 会在后台运行。</div><div v-for="run in runs.slice(0, 3)" :key="run.id" class="run-item"><div class="run-status-dot" :class="run.status.toLowerCase()"></div><div class="run-copy"><strong>文档处理 {{ run.status === 'COMPLETED' ? '完成' : run.status === 'FAILED' ? '失败' : '进行中' }}</strong><small>{{ run.currentNode || run.error || run.startedAt }}</small><div class="run-nodes"><span v-for="node in run.nodes" :key="node.type" :class="node.status.toLowerCase()">{{ node.type }} · {{ node.status }} · {{ node.durationMs }}ms</span></div></div><span class="run-date">{{ new Date(run.startedAt).toLocaleString('zh-CN') }}</span></div></div>
      </section>
      <section v-else class="pipeline-editor-card empty-editor"><div class="empty-doc-icon">⌘</div><h2>创建您的第一个入库流程</h2><p>使用默认节点开始编排文档处理步骤。</p><el-button type="primary" @click="create">新建流程</el-button></section>
    </div>
  </div>
</template>
