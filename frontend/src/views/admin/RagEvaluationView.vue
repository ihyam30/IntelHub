<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, type KnowledgeBase } from '../../api'

type EvaluationCase = { id: string; title: string; question: string; expectedSourceContains: string; expectedEvidence: string[] }
type Dataset = { version: string; cases: EvaluationCase[] }
type EvaluationHit = { chunkId: string; filename: string; chunkIndex: number; excerpt: string; expectedSource: boolean }
type CaseResult = { id: string; title: string; question: string; expectedSourceContains: string; sourceRank: number; matchedEvidence: string[]; passed: boolean; error: string | null; retrieved: EvaluationHit[] }
type RunResult = {
  knowledgeBaseId: string
  datasetVersion: string
  metrics: { caseCount: number; passedCases: number; sourceHitAt1: number; sourceRecallAt6: number; mrrAt6: number; evidenceCoverage: number; topK: number; durationMs: number }
  results: CaseResult[]
}

const knowledgeBases = ref<KnowledgeBase[]>([])
const dataset = ref<Dataset | null>(null)
const result = ref<RunResult | null>(null)
const selectedKnowledgeBaseId = ref('')
const loading = ref(false)
const running = ref(false)
const resultKbName = computed(() => {
  const resultKnowledgeBaseId = result.value?.knowledgeBaseId
  if (!resultKnowledgeBaseId) return ''
  return knowledgeBases.value.find(item => item.id === resultKnowledgeBaseId)?.name || resultKnowledgeBaseId
})

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try {
    const [bases, cases] = await Promise.all([
      http.get('/knowledge-bases') as Promise<KnowledgeBase[]>,
      http.get('/admin/evaluation/cases') as Promise<Dataset>,
    ])
    knowledgeBases.value = bases
    dataset.value = cases
    selectedKnowledgeBaseId.value ||= bases[0]?.id || ''
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评测数据加载失败')
  } finally { loading.value = false }
}

async function runEvaluation() {
  if (!selectedKnowledgeBaseId.value || running.value) return
  running.value = true
  try {
    result.value = await http.post('/admin/evaluation/run', { knowledgeBaseId: selectedKnowledgeBaseId.value }) as RunResult
    ElMessage.success(`评测完成：${result.value.metrics.passedCases}/${result.value.metrics.caseCount} 项通过`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '问答评测失败')
  } finally { running.value = false }
}

function percent(value: number) { return `${(value * 100).toFixed(1)}%` }
function rankLabel(rank: number) { return rank ? `第 ${rank} 位命中` : 'Top 6 未命中' }
</script>

<template>
  <section class="page-content rag-evaluation-page" v-loading="loading">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 质量评估 <i>/</i> 问答评测</div><h1>问答评测</h1><p>重复运行固定问题集，检查混合检索能否召回标注来源和证据片段。</p></div>
      <div class="console-heading-actions">
        <el-select v-model="selectedKnowledgeBaseId" class="evaluation-kb-select" placeholder="选择知识库" :disabled="running">
          <el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id" />
        </el-select>
        <el-button type="primary" :loading="running" :disabled="!selectedKnowledgeBaseId" @click="runEvaluation">▶ 运行评测</el-button>
      </div>
    </div>

    <div class="evaluation-note"><strong>评测方式</strong><span>固定问题直接走向量与关键词召回、RRF 融合及证据阈值；不调用 Chat 生成或 Rerank，减少变量，便于比较多次运行结果。需要先在所选知识库中上传对应的演示资料。</span><small>数据集版本 {{ dataset?.version || '—' }} · Top 6</small></div>

    <section v-if="result" class="console-card evaluation-results-card">
      <header class="console-card-heading"><div><h2>本次评测结果</h2><p>评测知识库：{{ resultKbName }} · {{ result.metrics.caseCount }} 个问题 · 用时 {{ result.metrics.durationMs }} ms</p></div><el-tag type="success" effect="light">可重复运行</el-tag></header>
      <div class="evaluation-metrics">
        <article><small>通过用例</small><strong>{{ result.metrics.passedCases }}<em> / {{ result.metrics.caseCount }}</em></strong></article>
        <article><small>来源 Hit@1</small><strong>{{ percent(result.metrics.sourceHitAt1) }}</strong></article>
        <article><small>来源 Recall@6</small><strong>{{ percent(result.metrics.sourceRecallAt6) }}</strong></article>
        <article><small>MRR@6</small><strong>{{ result.metrics.mrrAt6.toFixed(3) }}</strong></article>
        <article><small>证据词覆盖率</small><strong>{{ percent(result.metrics.evidenceCoverage) }}</strong></article>
      </div>
      <div class="evaluation-case-list">
        <article v-for="row in result.results" :key="row.id" class="evaluation-case-result">
          <header><div><strong>{{ row.title }}</strong><p>{{ row.question }}</p></div><el-tag size="small" :type="row.passed ? 'success' : row.error ? 'danger' : 'warning'">{{ row.error ? '检索失败' : row.passed ? '通过' : '未通过' }}</el-tag></header>
          <div class="evaluation-case-meta"><span>期望来源：<code>{{ row.expectedSourceContains }}</code></span><span :class="row.sourceRank ? 'hit' : 'miss'">{{ rankLabel(row.sourceRank) }}</span></div>
          <div v-if="row.error" class="evaluation-error">{{ row.error }}</div>
          <div class="evaluation-evidence"><small>命中证据</small><el-tag v-for="evidence in row.matchedEvidence" :key="evidence" size="small" type="success" effect="plain">{{ evidence }}</el-tag><span v-if="!row.matchedEvidence.length">暂无期望证据词</span></div>
          <details v-if="row.retrieved.length" class="evaluation-hits"><summary>查看 {{ row.retrieved.length }} 条召回片段</summary><div v-for="(hit, index) in row.retrieved" :key="hit.chunkId" class="evaluation-hit"><div><b>#{{ index + 1 }} · {{ hit.filename }}</b><el-tag v-if="hit.expectedSource" size="small" type="success">标注来源</el-tag><small>{{ hit.chunkIndex < 0 ? '关键词结果' : `片段 ${hit.chunkIndex + 1}` }}</small></div><p>{{ hit.excerpt }}</p></div></details>
          <div v-else class="evaluation-no-hits">没有召回片段</div>
        </article>
      </div>
    </section>

    <section class="console-card evaluation-dataset-card">
      <header class="console-card-heading"><div><h2>固定问答集</h2><p>题目、期望来源和证据词随项目版本保存；可在资源文件中维护基准。</p></div><span class="evaluation-count">{{ dataset?.cases.length || 0 }} 题</span></header>
      <el-table :data="dataset?.cases || []" row-key="id" empty-text="没有评测问题">
        <el-table-column prop="title" label="主题" min-width="160" />
        <el-table-column prop="question" label="问题" min-width="300" />
        <el-table-column prop="expectedSourceContains" label="期望来源文件名" min-width="240" />
        <el-table-column label="证据词" min-width="240"><template #default="scope">{{ scope.row.expectedEvidence.join('、') }}</template></el-table-column>
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.evaluation-kb-select { width: 240px; }
.evaluation-note { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 14px 18px; margin-bottom: 18px; border: 1px solid #e5e8f4; border-radius: 10px; background: #fafaff; color: #7d8599; font-size: 11px; line-height: 1.6; }
.evaluation-note strong { color: #5964d5; }
.evaluation-note small { color: #9aa1b1; white-space: nowrap; }
.evaluation-results-card, .evaluation-dataset-card { margin-bottom: 18px; }
.evaluation-metrics { display: grid; grid-template-columns: repeat(5,minmax(0,1fr)); gap: 10px; padding: 0 18px 18px; }
.evaluation-metrics article { padding: 14px; border-radius: 9px; background: #f8f9fc; }
.evaluation-metrics small, .evaluation-metrics strong { display: block; }
.evaluation-metrics small { color: #8d95a8; font-size: 10px; }
.evaluation-metrics strong { margin-top: 7px; color: #222a3c; font-size: 22px; }
.evaluation-metrics em { color: #99a0b0; font-size: 12px; font-style: normal; font-weight: 500; }
.evaluation-case-list { display: grid; gap: 10px; padding: 0 18px 18px; }
.evaluation-case-result { padding: 15px; border: 1px solid #eceef4; border-radius: 9px; }
.evaluation-case-result>header { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.evaluation-case-result>header strong { color: #31394d; font-size: 12px; }
.evaluation-case-result>header p { margin: 5px 0 0; color: #778096; font-size: 11px; }
.evaluation-case-meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin-top: 11px; color: #8e96a9; font-size: 10px; }
.evaluation-case-meta code { color: #5f68ce; }
.evaluation-case-meta .hit { color: #23a27e; }.evaluation-case-meta .miss { color: #d68a37; }
.evaluation-evidence { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 11px; }.evaluation-evidence small { color: #949bad; margin-right: 2px; }.evaluation-evidence>span { color: #a0a6b6; font-size: 10px; }
.evaluation-hits { margin-top: 12px; border-top: 1px solid #f0f1f5; padding-top: 10px; color: #737c91; font-size: 10px; }.evaluation-hits summary { cursor: pointer; color: #5f68ce; }.evaluation-hit { margin-top: 9px; padding: 10px; background: #fafbfe; border-radius: 7px; }.evaluation-hit>div { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }.evaluation-hit b { color: #555e73; }.evaluation-hit small { color: #9da4b4; }.evaluation-hit p { margin: 7px 0 0; color: #737c91; line-height: 1.6; white-space: pre-wrap; }
.evaluation-no-hits,.evaluation-error { margin-top: 10px; color: #bd7c49; font-size: 10px; }.evaluation-count { color: #858da0; font-size: 11px; }
@media (max-width: 760px) { .evaluation-note { grid-template-columns: 1fr; }.evaluation-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); }.evaluation-kb-select { width: 165px; } }
</style>
