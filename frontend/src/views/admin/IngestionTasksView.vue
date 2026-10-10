<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api'

type TaskNode = { type: string; status: string; durationMs: number; error?: string; startedAt?: string; finishedAt?: string }
type Task = { id: string; documentId: string; documentName: string; ownerId: string; username: string; pipelineName: string; status: string; currentNode?: string; error?: string; startedAt: string; finishedAt?: string; nodes: TaskNode[] }

const tasks = ref<Task[]>([])
const loading = ref(false)
const status = ref('ALL')
const query = ref('')
const appliedQuery = ref('')
const counts = computed(() => ({
  total: tasks.value.length,
  running: tasks.value.filter(item => ['QUEUED', 'PROCESSING'].includes(item.status)).length,
  completed: tasks.value.filter(item => item.status === 'COMPLETED').length,
  failed: tasks.value.filter(item => item.status === 'FAILED').length,
}))

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try {
    tasks.value = await http.get('/admin/ingestion-runs', { params: {
      status: status.value === 'ALL' ? undefined : status.value,
      query: appliedQuery.value,
      limit: 200,
    } }) as Task[]
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '任务加载失败') }
  finally { loading.value = false }
}

async function retry(task: Task) {
  try { await ElMessageBox.confirm(`重新处理文档“${task.documentName}”？`, '重试入库任务', { type: 'warning' }) }
  catch { return }
  try {
    await http.post(`/documents/${task.documentId}/retry`)
    ElMessage.success('任务已重新加入队列')
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '任务重试失败') }
}

function statusLabel(value: string) {
  return ({ QUEUED: '排队中', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[value] || value
}
function statusType(value: string) {
  return ({ QUEUED: 'info', PROCESSING: 'warning', COMPLETED: 'success', FAILED: 'danger' } as Record<string, string>)[value] || 'info'
}
function nodeLabel(value: string) {
  return ({ parse: '解析', normalize: '规范化', chunk: '切分', metadata: '元数据', embedding: '向量化', index: '索引' } as Record<string, string>)[value] || value
}
function time(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
</script>

<template>
  <section class="page-content admin-task-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 数据通道 <i>/</i> 流水线任务</div><h1>流水线任务</h1><p>查看每份文档的异步处理状态、节点耗时与失败信息。</p></div>
      <div class="console-heading-actions"><RouterLink class="console-link-button" to="/admin/pipelines">⌘ 流水线管理</RouterLink><el-button :loading="loading" @click="load">⟳ 刷新</el-button></div>
    </div>
    <div class="task-metric-grid">
      <article><span class="task-metric-icon blue">▣</span><div><small>当前结果</small><strong>{{ counts.total }}</strong></div><span>最多 200 条</span></article>
      <article><span class="task-metric-icon amber">◷</span><div><small>排队 / 处理中</small><strong>{{ counts.running }}</strong></div><span>异步任务</span></article>
      <article><span class="task-metric-icon green">✓</span><div><small>已完成</small><strong>{{ counts.completed }}</strong></div><span>处理成功</span></article>
      <article><span class="task-metric-icon red">!</span><div><small>失败</small><strong>{{ counts.failed }}</strong></div><span>可从此处重试</span></article>
    </div>
    <section class="console-card task-list-card">
      <header class="console-card-heading"><div><h2>执行记录</h2><p>记录包含 Pipeline 节点顺序、状态和耗时。</p></div><div class="task-filters">
        <el-input v-model="query" placeholder="搜索文档或用户" clearable @keyup.enter="appliedQuery = query; load()" />
        <el-select v-model="status" @change="load"><el-option label="全部状态" value="ALL" /><el-option label="排队中" value="QUEUED" /><el-option label="处理中" value="PROCESSING" /><el-option label="已完成" value="COMPLETED" /><el-option label="失败" value="FAILED" /></el-select>
        <el-button @click="appliedQuery = query; load()">搜索</el-button>
      </div></header>
      <el-table v-loading="loading" :data="tasks" row-key="id" class="task-table" empty-text="暂无流水线任务">
        <el-table-column type="expand" width="42"><template #default="scope"><div class="task-node-list"><article v-for="(node, index) in scope.row.nodes" :key="`${node.type}-${index}`" class="task-node" :class="node.status.toLowerCase()"><span class="task-node-number">{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ nodeLabel(node.type) }}</strong><small>{{ node.error || time(node.startedAt) }}</small></div><el-tag size="small" :type="statusType(node.status) as any">{{ statusLabel(node.status) }}</el-tag><b>{{ node.durationMs }} ms</b></article><el-empty v-if="!scope.row.nodes.length" description="没有节点执行记录" :image-size="52" /></div></template></el-table-column>
        <el-table-column label="文档" min-width="200"><template #default="scope"><div class="task-document"><strong>{{ scope.row.documentName }}</strong><small>{{ scope.row.username }} · {{ scope.row.pipelineName }}</small></div></template></el-table-column>
        <el-table-column label="状态" width="118"><template #default="scope"><el-tag :type="statusType(scope.row.status) as any" effect="light">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="当前节点" min-width="120"><template #default="scope">{{ scope.row.currentNode ? nodeLabel(scope.row.currentNode) : '—' }}</template></el-table-column>
        <el-table-column label="开始时间" min-width="175"><template #default="scope">{{ time(scope.row.startedAt) }}</template></el-table-column>
        <el-table-column label="详情 / 操作" min-width="165"><template #default="scope"><span v-if="scope.row.error" class="task-error" :title="scope.row.error">{{ scope.row.error }}</span><el-button v-if="scope.row.status === 'FAILED'" link type="primary" @click="retry(scope.row)">重试</el-button><span v-else-if="!scope.row.error" class="task-no-error">{{ scope.row.finishedAt ? `完成于 ${time(scope.row.finishedAt)}` : '运行中' }}</span></template></el-table-column>
      </el-table>
      <footer class="task-list-foot">列表按开始时间倒序，最多展示最近 200 次入库执行。</footer>
    </section>
  </section>
</template>
