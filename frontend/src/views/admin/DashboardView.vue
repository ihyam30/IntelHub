<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '../../api'

type Dashboard = { users: number; knowledgeBases: number; documents: number; conversations: number; traces24h: number; failedDocuments: number }
type Trace = { id: string; query: string; status: string; modelId?: string; durationMs: number; createdAt: string }

const stats = ref<Dashboard>({ users: 0, knowledgeBases: 0, documents: 0, conversations: 0, traces24h: 0, failedDocuments: 0 })
const traces = ref<Trace[]>([])
const loading = ref(false)
const refreshedAt = ref(new Date())

onMounted(() => { void refresh() })

async function refresh() {
  loading.value = true
  try {
    const [dashboard, recent] = await Promise.all([
      http.get('/admin/dashboard') as Promise<Dashboard>,
      http.get('/traces?limit=6') as Promise<Trace[]>,
    ])
    stats.value = dashboard
    traces.value = recent
    refreshedAt.value = new Date()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '仪表盘加载失败')
  } finally { loading.value = false }
}

function time(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
</script>

<template>
  <section class="page-content console-dashboard" v-loading="loading">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> Dashboard</div><h1>Dashboard</h1><p>IntelHub 知识问答系统运行概览</p></div>
      <div class="console-heading-actions"><span class="console-updated"><i></i>{{ refreshedAt.toLocaleTimeString('zh-CN') }}</span><el-button @click="refresh">⟳ 刷新</el-button></div>
    </div>
    <section class="console-card dashboard-metrics">
      <header class="console-card-heading"><div><h2>核心指标</h2><p>当前系统累计数据与近 24 小时问答量</p></div></header>
      <div class="dashboard-metric-grid">
        <article class="dashboard-metric"><span class="metric-symbol blue">♙</span><div><small>注册用户</small><strong>{{ stats.users }}</strong></div><span class="metric-note">账号</span></article>
        <article class="dashboard-metric"><span class="metric-symbol violet">▤</span><div><small>知识库</small><strong>{{ stats.knowledgeBases }}</strong></div><span class="metric-note">资料空间</span></article>
        <article class="dashboard-metric"><span class="metric-symbol cyan">▧</span><div><small>文档数量</small><strong>{{ stats.documents }}</strong></div><span class="metric-note">{{ stats.failedDocuments }} 个失败</span></article>
        <article class="dashboard-metric"><span class="metric-symbol amber">ϟ</span><div><small>近 24h 问答</small><strong>{{ stats.traces24h }}</strong></div><span class="metric-note">{{ stats.conversations }} 个会话</span></article>
      </div>
    </section>
    <div class="dashboard-columns">
      <section class="console-card dashboard-flow-card">
        <header class="console-card-heading"><div><h2>问答链路</h2><p>当前系统处理一条问题请求的主要步骤</p></div><el-tag type="success" effect="light"><i class="health-dot"></i>服务就绪</el-tag></header>
        <div class="dashboard-flow">
          <div><span>01</span><strong>身份与权限</strong><small>用户、知识库校验</small></div><b>→</b><div><span>02</span><strong>混合检索</strong><small>向量 + 关键词</small></div><b>→</b><div><span>03</span><strong>融合与重排</strong><small>RRF + Rerank</small></div><b>→</b><div><span>04</span><strong>模型回答</strong><small>SSE 与引用返回</small></div>
        </div>
        <footer class="dashboard-flow-footer">系统当前可展示的指标来自 PostgreSQL 中的账号、知识库、文档、会话与 Trace 记录。</footer>
      </section>
      <section class="console-card dashboard-ops-card">
      <header class="console-card-heading"><div><h2>运营概览</h2><p>最近问答执行情况</p></div><RouterLink to="/admin/traces">查看 Trace →</RouterLink></header>
        <div class="dashboard-ops-stats"><div><small>问答请求</small><strong>{{ stats.traces24h }}</strong><span>近 24 小时</span></div><div><small>入库失败</small><strong :class="{ danger: stats.failedDocuments > 0 }">{{ stats.failedDocuments }}</strong><span>当前文档</span></div></div>
        <div class="dashboard-hint"><span>ⓘ</span> 本项目当前没有按时间聚合的性能趋势 API，因此这里显示可核验的实时累计指标，不绘制模拟趋势图。</div>
      </section>
    </div>
    <section class="console-card dashboard-traces-card">
      <header class="console-card-heading"><div><h2>最近的问答 Trace</h2><p>用于快速定位模型、状态和耗时</p></div><RouterLink to="/admin/traces">全部记录 →</RouterLink></header>
      <div v-if="traces.length" class="dashboard-trace-list">
        <article v-for="trace in traces" :key="trace.id" class="dashboard-trace-row">
          <span class="dashboard-trace-state" :class="trace.status.toLowerCase()">{{ trace.status === 'COMPLETED' ? '✓' : '!' }}</span>
          <div class="dashboard-trace-query"><strong>{{ trace.query || '（无问题文本）' }}</strong><small>{{ trace.id }} · {{ time(trace.createdAt) }}</small></div>
          <el-tag size="small" :type="trace.status === 'COMPLETED' ? 'success' : trace.status === 'FAILED' ? 'danger' : 'warning'">{{ trace.status }}</el-tag>
          <span class="dashboard-trace-model">{{ trace.modelId || '模型未记录' }}</span><b>{{ trace.durationMs }} ms</b>
        </article>
      </div>
      <el-empty v-else description="还没有问答记录" :image-size="70" />
    </section>
    <section class="dashboard-shortcuts">
      <RouterLink to="/admin/knowledge"><span>▤</span><strong>管理知识库</strong><small>查看文档与已切分片段</small><b>↗</b></RouterLink>
      <RouterLink to="/admin/pipelines"><span>⌘</span><strong>编排入库流程</strong><small>调整处理节点并查看运行</small><b>↗</b></RouterLink>
      <RouterLink to="/admin/models"><span>✳</span><strong>模型与路由</strong><small>检查 Chat、Embedding、Rerank</small><b>↗</b></RouterLink>
    </section>
  </section>
</template>
