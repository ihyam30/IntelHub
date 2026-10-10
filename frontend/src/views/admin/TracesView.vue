<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '../../api'

type Trace = { id: string; ownerId: string; username: string; conversationId: string | null; query: string; retrieval: string; modelId: string | null; status: string; durationMs: number; error: string | null; createdAt: string }
type TraceStage = { name: string; status: string; durationMs: number; error?: string; details?: Record<string, any> }
const rows = ref<Trace[]>([])
const loading = ref(false)
const search = ref('')
const status = ref('ALL')
const selected = ref<Trace | null>(null)
const detailsOpen = ref(false)
const retrieval = computed(() => {
  if (!selected.value) return {} as Record<string, any>
  try { return JSON.parse(selected.value.retrieval || '{}') as Record<string, any> }
  catch { return {} as Record<string, any> }
})
const stages = computed<TraceStage[]>(() => Array.isArray(retrieval.value.stages) ? retrieval.value.stages as TraceStage[] : [])
const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return rows.value.filter(row => (status.value === 'ALL' || row.status === status.value)
    && (!query || `${row.id} ${row.query} ${row.username} ${row.modelId || ''}`.toLocaleLowerCase().includes(query)))
})
const stats = computed(() => ({
  completed: rows.value.filter(row => row.status === 'COMPLETED').length,
  failed: rows.value.filter(row => row.status === 'FAILED').length,
  running: rows.value.filter(row => ['QUEUED', 'PROCESSING'].includes(row.status)).length,
  average: rows.value.length ? Math.round(rows.value.reduce((total, row) => total + row.durationMs, 0) / rows.value.length) : 0,
}))

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { rows.value = await http.get('/traces', { params: { limit: 200 } }) as Trace[] }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : 'Trace 加载失败') }
  finally { loading.value = false }
}

function openDetails(row: Trace) { selected.value = row; detailsOpen.value = true }
function retrievalOf(value: string) { try { return JSON.parse(value || '{}') as Record<string, any> } catch { return {} as Record<string, any> } }
function statusLabel(value: string) { return ({ COMPLETED: '成功', FAILED: '失败', NEEDS_CLARIFICATION: '待澄清', PROCESSING: '运行中', QUEUED: '排队中' } as Record<string, string>)[value] || value }
function statusType(value: string) { return ({ COMPLETED: 'success', FAILED: 'danger', NEEDS_CLARIFICATION: 'warning', PROCESSING: 'warning', QUEUED: 'info' } as Record<string, string>)[value] || 'info' }
function stageLabel(value: string) { return ({ context_rewrite: '上下文改写', query_rewrite: '问题改写与拆分', intent_classification: '意图识别与路由', vector_retrieval: '向量召回', keyword_retrieval: '关键词召回', rrf_fusion: 'RRF 融合', rerank: '重排', model_routing: '模型路由与生成' } as Record<string, string>)[value] || value }
function stageStatusLabel(value: string) { return ({ SUCCESS: '成功', FAILED: '失败', FALLBACK: '已降级', SKIPPED: '跳过', SKIPPED_CIRCUIT: '熔断跳过', LOCAL_DEMO: '本地演示', LOCAL_FALLBACK: '本地降级' } as Record<string, string>)[value] || value }
function stageStatusType(value: string) { return ({ SUCCESS: 'success', FAILED: 'danger', FALLBACK: 'warning', LOCAL_FALLBACK: 'warning', SKIPPED_CIRCUIT: 'warning', SKIPPED: 'info', LOCAL_DEMO: 'info' } as Record<string, string>)[value] || 'info' }
function modelAttempts(stage: TraceStage) { return Array.isArray(stage.details?.attempts) ? stage.details.attempts as Array<Record<string, any>> : [] }
function detailsJson(value: unknown) { return JSON.stringify(value, null, 2) }
function time(value: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
function valueLabel(value: unknown) { return value === true ? '是' : value === false ? '否' : value == null || value === '' ? '—' : String(value) }
</script>

<template>
  <section class="page-content console-admin-page traces-admin-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 运维 <i>/</i> 链路追踪</div><h1>链路追踪</h1><p>查看改写、意图、多路召回、重排和模型切换的分阶段耗时与错误。</p></div>
      <div class="console-heading-actions"><el-input v-model="search" class="trace-search" placeholder="搜索 Trace ID、问题或用户" clearable /><el-select v-model="status" class="trace-status"><el-option label="全部状态" value="ALL" /><el-option label="成功" value="COMPLETED" /><el-option label="失败" value="FAILED" /><el-option label="待澄清" value="NEEDS_CLARIFICATION" /><el-option label="运行中" value="PROCESSING" /></el-select><el-button @click="load">⟳ 刷新</el-button></div>
    </div>
    <div class="intent-metric-grid trace-metrics"><article><span>成功请求</span><strong>{{ stats.completed }}</strong></article><article><span>失败请求</span><strong>{{ stats.failed }}</strong></article><article><span>运行中</span><strong>{{ stats.running }}</strong></article><article><span>平均耗时</span><strong>{{ stats.average }}<small> ms</small></strong></article></div>
    <section class="console-card trace-list-card">
      <header class="console-card-heading"><div><h2>最近问答请求</h2><p>当前接口最多读取最近 200 条；统计基于此列表，不代表全量历史。</p></div><span class="trace-row-count">{{ filtered.length }} 条</span></header>
      <el-table v-loading="loading" :data="filtered" row-key="id" class="task-table trace-table" empty-text="暂无问答 Trace">
        <el-table-column label="请求" min-width="310"><template #default="scope"><button class="trace-query-button" @click="openDetails(scope.row)"><strong>{{ scope.row.query || '（无问题文本）' }}</strong><small>{{ scope.row.id }}</small></button></template></el-table-column>
        <el-table-column prop="username" label="用户" min-width="120" />
        <el-table-column label="状态" width="120"><template #default="scope"><el-tag size="small" :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="modelId" label="模型" min-width="130" />
        <el-table-column label="召回" width="145"><template #default="scope"><span class="trace-recall-cell">{{ retrievalOf(scope.row.retrieval).vectorHits || 0 }} 向量 · {{ retrievalOf(scope.row.retrieval).keywordHits || 0 }} 关键词</span></template></el-table-column>
        <el-table-column label="耗时" width="100"><template #default="scope">{{ scope.row.durationMs }} ms</template></el-table-column>
        <el-table-column label="时间" min-width="170"><template #default="scope">{{ time(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="详情" width="82" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetails(scope.row)">查看</el-button></template></el-table-column>
      </el-table>
      <footer class="mapping-list-foot">结果 {{ filtered.length }} / {{ rows.length }}</footer>
    </section>
    <el-dialog v-model="detailsOpen" title="问答 Trace 详情" width="min(820px,94vw)" top="6vh">
      <template v-if="selected">
        <div class="trace-detail-summary"><div><small>Trace ID</small><strong>{{ selected.id }}</strong></div><el-tag :type="statusType(selected.status)">{{ statusLabel(selected.status) }}</el-tag></div>
        <div class="trace-detail-question"><small>用户问题</small><p>{{ selected.query }}</p></div>
        <div class="trace-detail-grid"><div><small>用户</small><strong>{{ selected.username }} · {{ selected.ownerId }}</strong></div><div><small>模型</small><strong>{{ selected.modelId || '—' }}</strong></div><div><small>耗时</small><strong>{{ selected.durationMs }} ms</strong></div><div><small>创建时间</small><strong>{{ time(selected.createdAt) }}</strong></div></div>
        <div class="trace-detail-section"><h3>检索与链路字段</h3><div class="trace-detail-grid"><div v-for="key in ['vectorHits','keywordHits','citations','vectorAvailable','keywordAvailable','retrievalQuery','rewrittenQuery','memoryUsed','intentId','mcpToolUsed','clarificationRequired']" :key="key"><small>{{ ({ vectorHits:'向量命中', keywordHits:'关键词命中', citations:'引用数', vectorAvailable:'向量可用', keywordAvailable:'关键词可用', retrievalQuery:'检索查询', rewrittenQuery:'上下文改写', memoryUsed:'使用记忆', intentId:'意图 ID', mcpToolUsed:'调用 MCP 工具', clarificationRequired:'需要澄清' } as Record<string,string>)[key] }}</small><strong>{{ valueLabel(retrieval[key]) }}</strong></div></div></div>
        <div class="trace-detail-section trace-stages-section"><h3>阶段耗时与诊断 <small>{{ stages.length }} 个阶段</small></h3>
          <div v-if="stages.length" class="trace-stage-list">
            <article v-for="(stage, index) in stages" :key="`${stage.name}-${index}`" class="trace-stage-item" :class="`is-${stage.status.toLowerCase()}`">
              <div class="trace-stage-heading"><div><strong>{{ stageLabel(stage.name) }}</strong><el-tag size="small" :type="stageStatusType(stage.status)">{{ stageStatusLabel(stage.status) }}</el-tag></div><span>{{ stage.durationMs }} ms</span></div>
              <p v-if="stage.error" class="trace-stage-error">{{ stage.error }}</p>
              <div v-if="stage.name === 'model_routing' && modelAttempts(stage).length" class="trace-model-attempts">
                <div v-for="(attempt, attemptIndex) in modelAttempts(stage)" :key="`${attempt.providerId}-${attemptIndex}`" class="trace-model-attempt">
                  <div><strong>{{ attempt.model || attempt.providerId }}</strong><small>{{ attempt.providerId }}</small><el-tag size="small" :type="stageStatusType(attempt.status)">{{ stageStatusLabel(attempt.status) }}</el-tag><span>{{ attempt.durationMs }} ms</span></div>
                  <p v-if="attempt.error">{{ attempt.error }}</p>
                </div>
              </div>
              <details v-if="stage.details && stage.name !== 'model_routing'" class="trace-stage-details"><summary>阶段参数</summary><pre>{{ detailsJson(stage.details) }}</pre></details>
              <details v-else-if="stage.details" class="trace-stage-details"><summary>路由参数</summary><pre>{{ detailsJson(stage.details) }}</pre></details>
            </article>
          </div>
          <div v-else class="trace-stages-empty">这条 Trace 没有阶段明细（可能由旧版本生成）。</div>
        </div>
        <div v-if="selected.error" class="trace-detail-error"><strong>错误信息</strong><p>{{ selected.error }}</p></div>
        <details class="trace-raw-details"><summary>原始检索 Trace</summary><pre>{{ JSON.stringify(retrieval, null, 2) }}</pre></details>
      </template>
      <template #footer><el-button @click="detailsOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
