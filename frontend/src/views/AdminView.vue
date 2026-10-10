<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../api'
import { useRoute, useRouter } from 'vue-router'
import ModelManager from './admin/ModelManager.vue'
import IntentManager from './admin/IntentManager.vue'

type Tab = 'overview' | 'users' | 'models' | 'intents' | 'traces' | 'settings'
const tab = ref<Tab>('overview')
const route = useRoute()
const router = useRouter()
const modelRefresh = ref(0)
const dashboard = ref({ users: 0, knowledgeBases: 0, documents: 0, conversations: 0, traces24h: 0, failedDocuments: 0 })
const users = ref<any[]>([])
const models = ref<any[]>([])
const intents = ref<any[]>([])
const traces = ref<any[]>([])
const settings = ref({ maxConcurrent: 4, perUserRequestsPerMinute: 20, maxWaitSeconds: 30 })
const search = ref('')
const saving = ref(false)
const tabs: { key: Tab; label: string; icon: string }[] = [
  { key: 'overview', label: '系统概览', icon: '◫' }, { key: 'users', label: '用户管理', icon: '◎' },
  { key: 'models', label: '模型路由', icon: '✳' }, { key: 'intents', label: '意图树', icon: '⌘' }, { key: 'traces', label: '问答追踪', icon: '⌁' },
  { key: 'settings', label: '系统设置', icon: '⚙' },
]
const title = computed(() => tabs.find(item => item.key === tab.value)?.label || '管理中心')

watch(() => route.query.tab, (value) => {
  const next = typeof value === 'string' && tabs.some(item => item.key === value) ? value as Tab : 'overview'
  tab.value = next
  void loadTab()
}, { immediate: true })

async function loadTab() {
  if (tab.value === 'overview') dashboard.value = await http.get('/admin/dashboard') as typeof dashboard.value
  if (tab.value === 'users') users.value = await http.get('/admin/users', { params: { query: search.value } }) as any[]
  if (tab.value === 'models') models.value = await http.get('/admin/models') as any[]
  if (tab.value === 'intents') intents.value = await http.get('/intents') as any[]
  if (tab.value === 'traces') traces.value = await http.get('/traces?limit=100') as any[]
  if (tab.value === 'settings') settings.value = await http.get('/admin/settings') as typeof settings.value
}

async function choose(next: Tab) {
  await router.replace({ query: { ...route.query, tab: next } })
}
async function refreshTab() { await loadTab(); modelRefresh.value++ }

async function toggleUser(user: any) {
  try {
    await http.patch(`/admin/users/${user.id}/enabled`, { enabled: !user.enabled })
    user.enabled = !user.enabled
    ElMessage.success(user.enabled ? '用户已启用' : '用户已停用')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败') }
}

async function saveSettings() {
  saving.value = true
  try {
    settings.value = await http.put('/admin/settings', { maxConcurrent: settings.value.maxConcurrent }) as typeof settings.value
    ElMessage.success('并发设置已更新')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { saving.value = false }
}

async function createIntent() {
  const nameResult = await ElMessageBox.prompt('输入意图名称', '添加意图', { inputValue: '新意图', inputPattern: /.+/, inputErrorMessage: '名称不能为空' }).catch(() => null)
  if (!nameResult) return
  const keywordsResult = await ElMessageBox.prompt('输入匹配关键词，以逗号分隔', '配置关键词', { inputValue: '关键词一，关键词二', inputPattern: /.+/, inputErrorMessage: '至少输入一个关键词' }).catch(() => null)
  if (!keywordsResult) return
  const keywords = keywordsResult.value.split(/[，,]/).map(word => word.trim()).filter(Boolean)
  try {
    await http.post('/intents', { name: nameResult.value, description: '', keywords, promptHint: '', enabled: true })
    await loadTab()
    ElMessage.success('意图已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
}

async function toggleIntent(intent: any) {
  try {
    await http.put(`/intents/${intent.id}`, { name: intent.name, description: intent.description, keywords: intent.keywords, promptHint: intent.promptHint, enabled: !intent.enabled, parentId: intent.parentId || null })
    intent.enabled = !intent.enabled
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新失败') }
}

async function deleteIntent(intent: any) {
  await ElMessageBox.confirm(`删除意图「${intent.name}」？`, '删除意图', { type: 'warning' })
  await http.delete(`/intents/${intent.id}`)
  intents.value = intents.value.filter(item => item.id !== intent.id)
}

function retrieval(value: string) {
  try { return JSON.parse(value) as Record<string, number> } catch { return {} }
}
</script>

<template>
  <div class="page-content admin-page">
    <div class="page-title-row"><div><div class="eyebrow"><span></span> ADMIN CONSOLE</div><h1>管理中心</h1><p>用户、模型、问答质量与运行状态的统一视图。</p></div><div class="admin-live"><span class="status-dot"></span> ADMIN MODE</div></div>
    <div class="admin-layout">
      <aside class="admin-nav-card"><span class="section-kicker">CONTROL PLANE</span><button v-for="item in tabs" :key="item.key" :class="{ active: tab === item.key }" @click="choose(item.key)"><span>{{ item.icon }}</span>{{ item.label }}<b>›</b></button><div class="admin-nav-foot"><div class="secure-ring">⌑</div><strong>访问受保护</strong><small>管理员权限仅对授权账号开放。</small></div></aside>
      <section class="admin-main-card">
        <div class="admin-content-heading"><div><div class="section-kicker">{{ title.toUpperCase() }}</div><h2>{{ title }}</h2></div><el-button text @click="refreshTab">⟳ 刷新</el-button></div>
        <ModelManager v-if="tab === 'models'" :key="modelRefresh" />
        <IntentManager v-else-if="tab === 'intents'" />
        <template v-else-if="tab === 'overview'"><div class="metric-grid"><div class="metric-card"><span>注册用户</span><strong>{{ dashboard.users }}</strong><small><i class="metric-dot violet"></i>系统账号</small></div><div class="metric-card"><span>知识库</span><strong>{{ dashboard.knowledgeBases }}</strong><small><i class="metric-dot cyan"></i>个人知识空间</small></div><div class="metric-card"><span>文档总量</span><strong>{{ dashboard.documents }}</strong><small><i class="metric-dot green"></i>{{ dashboard.failedDocuments }} 份处理失败</small></div><div class="metric-card"><span>问答请求</span><strong>{{ dashboard.traces24h }}</strong><small><i class="metric-dot amber"></i>近 24 小时</small></div></div><div class="overview-panels"><div class="admin-info-panel"><div class="panel-top"><div><span class="section-kicker">RAG SERVICE</span><h3>问答链路</h3></div><span class="service-badge"><i></i> READY</span></div><div class="service-flow"><span>认证</span><i>→</i><span>混合检索</span><i>→</i><span>RRF 融合</span><i>→</i><span>模型生成</span></div><p>每次请求都会记录检索命中数、模型、耗时和完成状态，便于定位答案质量问题。</p></div><div class="admin-info-panel"><div class="panel-top"><div><span class="section-kicker">MODEL HEALTH</span><h3>模型状态</h3></div><button class="text-link" @click="choose('models')">查看路由 →</button></div><div v-if="models.length" class="health-rows"><div v-for="model in models" :key="model.id"><span>{{ model.id }}</span><el-tag size="small" :type="model.state === 'CLOSED' ? 'success' : 'warning'">{{ model.state }}</el-tag></div></div><p v-else>模型状态会在打开「模型路由」后加载。</p></div></div></template>
        <template v-else-if="tab === 'users'"><div class="table-topline"><span>账户与角色</span><el-input v-model="search" placeholder="搜索用户名或显示名称" clearable @keyup.enter="loadTab" @clear="loadTab" /><el-button @click="loadTab">搜索</el-button></div><el-table :data="users" stripe><el-table-column prop="username" label="用户名" min-width="150" /><el-table-column prop="displayName" label="显示名称" min-width="150" /><el-table-column label="角色" width="130"><template #default="scope"><el-tag :type="scope.row.role === 'ADMIN' ? 'danger' : 'info'" effect="light">{{ scope.row.role }}</el-tag></template></el-table-column><el-table-column label="状态" width="120"><template #default="scope"><span class="user-status"><i :class="scope.row.enabled ? 'online' : ''"></i>{{ scope.row.enabled ? '正常' : '已停用' }}</span></template></el-table-column><el-table-column prop="createdAt" label="注册时间" min-width="180"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString('zh-CN') }}</template></el-table-column><el-table-column label="操作" width="120"><template #default="scope"><el-button text :type="scope.row.enabled ? 'danger' : 'primary'" @click="toggleUser(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table></template>
        <template v-else-if="tab === 'traces'"><div class="trace-summary"><span>最近的问答请求</span><small>每条记录包括检索命中数、模型、状态和耗时。</small></div><div v-if="traces.length" class="trace-list"><div v-for="trace in traces" :key="trace.id" class="trace-row"><div class="trace-status-icon" :class="trace.status.toLowerCase()">{{ trace.status === 'COMPLETED' ? '✓' : '!' }}</div><div class="trace-query"><strong>{{ trace.query }}</strong><small>{{ trace.username || '我的账号' }} · {{ new Date(trace.createdAt).toLocaleString('zh-CN') }}</small><div class="trace-chips"><span>向量 {{ retrieval(trace.retrieval).vectorHits || 0 }}</span><span>关键词 {{ retrieval(trace.retrieval).keywordHits || 0 }}</span><span>{{ trace.modelId || '—' }}</span></div></div><div class="trace-duration">{{ trace.durationMs }}<small>ms</small></div></div></div><div v-else class="runs-empty">暂无问答追踪记录。</div></template>
        <template v-else><div class="settings-section"><div class="setting-row"><div><strong>最大并发问答</strong><p>跨实例共享的并发许可数，修改后立即生效。</p></div><el-input-number v-model="settings.maxConcurrent" :min="1" :max="64" /></div><div class="setting-row"><div><strong>单用户请求上限</strong><p>用于保护共享模型配额。</p></div><div class="readonly-setting">{{ settings.perUserRequestsPerMinute }} 次 / 分钟</div></div><div class="setting-row"><div><strong>最大排队时间</strong><p>超过时间后向前端返回超时事件。</p></div><div class="readonly-setting">{{ settings.maxWaitSeconds }} 秒</div></div><el-button type="primary" :loading="saving" @click="saveSettings">保存设置</el-button></div></template>
      </section>
    </div>
  </div>
</template>
