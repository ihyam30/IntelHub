<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, type KnowledgeBase } from '../../api'

type Intent = { id: string; parentId: string | null; name: string; description: string; keywords: string[]; promptHint: string; enabled: boolean; updatedAt: string; knowledgeBaseIds: string[] }
const route = useRoute()
const treeMode = computed(() => route.path.endsWith('/tree'))
const items = ref<Intent[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editingId = ref('')
const search = ref('')
const draft = ref({ name: '', description: '', keywords: '', promptHint: '', enabled: true, parentId: '', knowledgeBaseIds: [] as string[] })
const roots = computed(() => items.value.filter(item => !item.parentId))
const filteredItems = computed(() => {
  const term = search.value.trim().toLocaleLowerCase()
  return term ? items.value.filter(item => `${item.name} ${item.description} ${item.keywords.join(' ')}`.toLocaleLowerCase().includes(term)) : items.value
})
const enabledCount = computed(() => items.value.filter(item => item.enabled).length)
const leafCount = computed(() => items.value.filter(item => item.parentId || item.keywords.length > 0).length)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try {
    const [intents, bases] = await Promise.all([http.get('/intents'), http.get('/knowledge-bases')])
    items.value = intents as unknown as Intent[]
    knowledgeBases.value = bases as unknown as KnowledgeBase[]
  }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '意图加载失败') }
  finally { loading.value = false }
}

function childrenOf(parentId: string) {
  const term = search.value.trim().toLocaleLowerCase()
  const root = items.value.find(item => item.id === parentId)
  const rootMatches = !!term && !!root && `${root.name} ${root.description} ${root.keywords.join(' ')}`.toLocaleLowerCase().includes(term)
  return items.value.filter(item => item.parentId === parentId && (!term || rootMatches || filteredItems.value.some(filtered => filtered.id === item.id)))
}
const treeRoots = computed(() => roots.value.filter(root => {
  const term = search.value.trim().toLocaleLowerCase()
  return !term || `${root.name} ${root.description} ${root.keywords.join(' ')}`.toLocaleLowerCase().includes(term)
    || items.value.some(item => item.parentId === root.id && filteredItems.value.some(filtered => filtered.id === item.id))
}))

function openCreate(parentId = '') {
  editingId.value = ''
  draft.value = { name: '', description: '', keywords: '', promptHint: '', enabled: true, parentId, knowledgeBaseIds: [] }
  dialog.value = true
}

function openEdit(item: Intent) {
  editingId.value = item.id
  draft.value = { name: item.name, description: item.description || '', keywords: item.keywords.join('，'), promptHint: item.promptHint || '', enabled: item.enabled, parentId: item.parentId || '', knowledgeBaseIds: item.knowledgeBaseIds || [] }
  dialog.value = true
}

function parseKeywords() { return draft.value.keywords.split(/[，,\n]/).map(word => word.trim()).filter(Boolean) }

async function save() {
  const keywords = parseKeywords()
  if (!draft.value.name.trim()) { ElMessage.warning('请填写意图名称'); return }
  if (draft.value.parentId && !keywords.length) { ElMessage.warning('子意图至少需要一个关键词'); return }
  saving.value = true
  const payload = { name: draft.value.name.trim(), description: draft.value.description.trim(), keywords, promptHint: draft.value.promptHint.trim(), enabled: draft.value.enabled, parentId: draft.value.parentId || null, knowledgeBaseIds: draft.value.knowledgeBaseIds }
  try {
    if (editingId.value) await http.put(`/intents/${editingId.value}`, payload)
    else await http.post('/intents', payload)
    dialog.value = false
    await load()
    ElMessage.success(editingId.value ? '意图已更新' : '意图已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { saving.value = false }
}

async function toggle(item: Intent) {
  try {
    const updated = await http.put(`/intents/${item.id}`, { name: item.name, description: item.description, keywords: item.keywords, promptHint: item.promptHint, enabled: !item.enabled, parentId: item.parentId, knowledgeBaseIds: item.knowledgeBaseIds || [] }) as Intent
    items.value = items.value.map(row => row.id === item.id ? updated : row)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '状态更新失败') }
}

function knowledgeBaseNames(ids: string[]) {
  return (ids || []).map(id => knowledgeBases.value.find(item => item.id === id)?.name).filter((name): name is string => !!name)
}

async function remove(item: Intent) {
  const childCount = items.value.filter(child => child.parentId === item.id).length
  try { await ElMessageBox.confirm(childCount ? `删除“${item.name}”会同时删除 ${childCount} 个子意图，是否继续？` : `确定删除意图“${item.name}”吗？`, '删除意图', { type: 'warning' }) }
  catch { return }
  try { await http.delete(`/intents/${item.id}`); await load(); ElMessage.success('意图已删除') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除失败') }
}
</script>

<template>
  <section class="page-content intent-admin-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 意图管理 <i>/</i> {{ treeMode ? '意图树配置' : '意图列表' }}</div><h1>{{ treeMode ? '意图树配置' : '意图列表' }}</h1><p>{{ treeMode ? '配置分类节点与子意图关键词，让问题路由更清晰。' : '检查意图规则、匹配词和启用状态。' }}</p></div>
      <div class="console-heading-actions"><RouterLink class="console-link-button" :to="treeMode ? '/admin/intents/list' : '/admin/intents/tree'">{{ treeMode ? '列表视图' : '树形视图' }}</RouterLink><el-button @click="load">⟳ 刷新</el-button><el-button type="primary" @click="openCreate()">＋ 新建{{ treeMode ? '根节点' : '意图' }}</el-button></div>
    </div>
    <div class="intent-metric-grid"><article><span>全部节点</span><strong>{{ items.length }}</strong></article><article><span>启用节点</span><strong>{{ enabledCount }}</strong></article><article><span>可分类规则</span><strong>{{ leafCount }}</strong></article><article><span>层级深度</span><strong>{{ roots.some(root => items.some(child => child.parentId === root.id)) ? '2' : '1' }}</strong></article></div>
    <section class="console-card intent-admin-card">
      <header class="console-card-heading"><div><h2>{{ treeMode ? '意图结构' : '分类规则' }}</h2><p>{{ treeMode ? '根节点可用于分组；具有关键词的节点参与意图分类。最多支持两层。' : '关键词采用包含匹配；低置信度或命中相近规则时会请求用户澄清。' }}</p></div><el-input v-model="search" class="intent-search" placeholder="搜索名称或关键词" clearable /></header>
      <div v-if="treeMode" v-loading="loading" class="intent-tree-list">
        <article v-for="root in treeRoots" :key="root.id" class="intent-tree-root">
          <header class="intent-tree-root-head"><span class="intent-tree-icon">◇</span><div class="intent-tree-copy"><strong>{{ root.name }}</strong><small>{{ root.description || (root.keywords.length ? `${root.keywords.length} 个匹配词` : '分组节点') }}</small><small v-if="knowledgeBaseNames(root.knowledgeBaseIds).length">定向知识库：{{ knowledgeBaseNames(root.knowledgeBaseIds).join('、') }}</small></div><el-tag size="small" :type="root.enabled ? 'success' : 'info'">{{ root.enabled ? '启用' : '停用' }}</el-tag><el-button link @click="toggle(root)">{{ root.enabled ? '停用' : '启用' }}</el-button><el-button link @click="openCreate(root.id)">＋ 子意图</el-button><el-button link @click="openEdit(root)">编辑</el-button><el-button link type="danger" @click="remove(root)">删除</el-button></header>
          <div v-if="root.keywords.length" class="intent-tree-keywords"><span v-for="keyword in root.keywords" :key="keyword">{{ keyword }}</span></div>
          <div v-if="childrenOf(root.id).length" class="intent-tree-children"><article v-for="child in childrenOf(root.id)" :key="child.id" class="intent-tree-child"><span class="intent-tree-branch">└</span><span class="intent-tree-icon child">◈</span><div class="intent-tree-copy"><strong>{{ child.name }}</strong><small>{{ child.description || child.promptHint || '意图分类规则' }}</small><small v-if="knowledgeBaseNames(child.knowledgeBaseIds).length">定向知识库：{{ knowledgeBaseNames(child.knowledgeBaseIds).join('、') }}</small><div class="intent-tree-keywords"><span v-for="keyword in child.keywords" :key="keyword">{{ keyword }}</span></div></div><el-tag size="small" :type="child.enabled ? 'success' : 'info'">{{ child.enabled ? '启用' : '停用' }}</el-tag><el-button link @click="toggle(child)">{{ child.enabled ? '停用' : '启用' }}</el-button><el-button link @click="openEdit(child)">编辑</el-button><el-button link type="danger" @click="remove(child)">删除</el-button></article></div>
          <div v-else class="intent-tree-no-children">暂无子意图。<button @click="openCreate(root.id)">添加一条规则</button></div>
        </article>
        <el-empty v-if="!treeRoots.length && !loading" description="没有意图节点" />
      </div>
      <el-table v-else v-loading="loading" :data="filteredItems" row-key="id" class="intent-table" empty-text="暂无意图规则">
        <el-table-column label="意图" min-width="190"><template #default="scope"><div class="intent-cell"><span class="intent-tree-icon child">◈</span><div><strong>{{ scope.row.name }}</strong><small>{{ scope.row.parentId ? roots.find(root => root.id === scope.row.parentId)?.name : '根节点' }}</small></div></div></template></el-table-column>
        <el-table-column label="关键词" min-width="240"><template #default="scope"><div class="intent-table-keywords"><span v-for="keyword in scope.row.keywords" :key="keyword">{{ keyword }}</span><small v-if="!scope.row.keywords.length">分组节点</small></div></template></el-table-column>
        <el-table-column label="定向知识库" min-width="180"><template #default="scope"><span>{{ knowledgeBaseNames(scope.row.knowledgeBaseIds).join('、') || '未配置' }}</span></template></el-table-column>
        <el-table-column prop="description" label="说明" min-width="180" />
        <el-table-column label="状态" width="95"><template #default="scope"><el-tag size="small" :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190"><template #default="scope"><el-button link @click="toggle(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="dialog" :title="editingId ? '编辑意图节点' : '新建意图节点'" width="min(640px,92vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="intent-edit-form">
        <el-form-item label="意图名称" required><el-input v-model="draft.name" maxlength="120" show-word-limit /></el-form-item>
        <el-form-item label="父节点"><el-select v-model="draft.parentId" clearable placeholder="设为根节点"><el-option v-for="root in roots.filter(root => root.id !== editingId)" :key="root.id" :label="root.name" :value="root.id" /></el-select><small>根节点可作为分类，也可以仅作为目录分组。</small></el-form-item>
        <el-form-item label="描述"><el-input v-model="draft.description" maxlength="500" /></el-form-item>
        <el-form-item label="匹配关键词"><el-input v-model="draft.keywords" type="textarea" :rows="3" placeholder="用逗号或换行分隔；子意图必须配置关键词" /></el-form-item>
        <el-form-item label="定向知识库"><el-select v-model="draft.knowledgeBaseIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="不选则使用用户当前选择的知识库"><el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id" /></el-select><small>命中此意图时将优先在所选知识库中检索；未配置则使用当前知识库。</small></el-form-item>
        <el-form-item label="回答提示"><el-input v-model="draft.promptHint" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="draft.enabled"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
