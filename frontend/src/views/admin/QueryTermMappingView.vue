<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api'

type Mapping = { id: string; sourceTerm: string; targetTerms: string[]; description: string; enabled: boolean; updatedAt: string }
const rows = ref<Mapping[]>([])
const loading = ref(false)
const saving = ref(false)
const search = ref('')
const dialog = ref(false)
const editingId = ref('')
const draft = ref({ sourceTerm: '', targetTerms: '', description: '', enabled: true })
const filteredRows = computed(() => {
  const term = search.value.trim().toLocaleLowerCase()
  return term ? rows.value.filter(row => `${row.sourceTerm} ${row.targetTerms.join(' ')} ${row.description}`.toLocaleLowerCase().includes(term)) : rows.value
})
const enabledCount = computed(() => rows.value.filter(row => row.enabled).length)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { rows.value = await http.get('/admin/query-mappings') as Mapping[] }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '关键词映射加载失败') }
  finally { loading.value = false }
}

function openCreate() {
  editingId.value = ''
  draft.value = { sourceTerm: '', targetTerms: '', description: '', enabled: true }
  dialog.value = true
}

function openEdit(row: Mapping) {
  editingId.value = row.id
  draft.value = { sourceTerm: row.sourceTerm, targetTerms: row.targetTerms.join('，'), description: row.description || '', enabled: row.enabled }
  dialog.value = true
}

async function save() {
  const sourceTerm = draft.value.sourceTerm.trim()
  const targetTerms = draft.value.targetTerms.split(/[，,\n]/).map(term => term.trim()).filter(Boolean)
  if (!sourceTerm || !targetTerms.length) { ElMessage.warning('请填写原词和至少一个目标词'); return }
  saving.value = true
  try {
    const payload = { sourceTerm, targetTerms: [...new Set(targetTerms)], description: draft.value.description.trim(), enabled: draft.value.enabled }
    if (editingId.value) await http.put(`/admin/query-mappings/${editingId.value}`, payload)
    else await http.post('/admin/query-mappings', payload)
    dialog.value = false
    await load()
    ElMessage.success(editingId.value ? '映射规则已更新' : '映射规则已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存映射规则失败') }
  finally { saving.value = false }
}

async function toggle(row: Mapping) {
  try {
    const updated = await http.put(`/admin/query-mappings/${row.id}`, { sourceTerm: row.sourceTerm, targetTerms: row.targetTerms, description: row.description, enabled: !row.enabled }) as Mapping
    rows.value = rows.value.map(item => item.id === row.id ? updated : item)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新状态失败') }
}

async function remove(row: Mapping) {
  try { await ElMessageBox.confirm(`删除“${row.sourceTerm}”映射规则？`, '删除映射', { type: 'warning' }) }
  catch { return }
  try { await http.delete(`/admin/query-mappings/${row.id}`); rows.value = rows.value.filter(item => item.id !== row.id); ElMessage.success('映射规则已删除') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除映射失败') }
}
</script>

<template>
  <section class="page-content console-admin-page mapping-admin-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 数据通道 <i>/</i> 关键词映射</div><h1>关键词映射管理</h1><p>查询命中原词时扩展同义词，改善关键词召回。映射只用于检索，不会改写模型问题。</p></div>
      <div class="console-heading-actions"><el-button @click="load">⟳ 刷新</el-button><el-button type="primary" @click="openCreate">＋ 新增映射</el-button></div>
    </div>
    <div class="intent-metric-grid mapping-metrics"><article><span>映射规则</span><strong>{{ rows.length }}</strong></article><article><span>已启用</span><strong>{{ enabledCount }}</strong></article><article><span>已停用</span><strong>{{ rows.length - enabledCount }}</strong></article></div>
    <section class="console-card mapping-list-card">
      <header class="console-card-heading"><div><h2>映射规则</h2><p>按原词匹配，目标词将附加到检索查询中；每条规则最多维护 20 个目标词。</p></div><el-input v-model="search" class="mapping-search" placeholder="搜索原词或目标词" clearable /></header>
      <el-table v-loading="loading" :data="filteredRows" row-key="id" class="task-table mapping-table" empty-text="暂无映射规则，新增规则后可扩展关键词召回">
        <el-table-column label="原词" min-width="150"><template #default="scope"><strong class="mapping-source">{{ scope.row.sourceTerm }}</strong></template></el-table-column>
        <el-table-column label="目标词" min-width="250"><template #default="scope"><div class="intent-table-keywords"><span v-for="term in scope.row.targetTerms" :key="term">{{ term }}</span></div></template></el-table-column>
        <el-table-column prop="description" label="说明" min-width="170" />
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag size="small" :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="205" fixed="right"><template #default="scope"><el-button link @click="toggle(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
      </el-table>
      <footer class="mapping-list-foot">共 {{ filteredRows.length }} 条规则</footer>
    </section>
    <el-dialog v-model="dialog" :title="editingId ? '编辑关键词映射' : '新增关键词映射'" width="min(620px,92vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="mapping-edit-form">
        <el-form-item label="原词" required><el-input v-model="draft.sourceTerm" maxlength="120" placeholder="例如：报销" /></el-form-item>
        <el-form-item label="目标词" required><el-input v-model="draft.targetTerms" type="textarea" :rows="3" placeholder="使用逗号或换行分隔，例如：费用报销，差旅报销" /><small>问句命中原词后，这些词将参与 BM25 关键词召回。</small></el-form-item>
        <el-form-item label="说明"><el-input v-model="draft.description" maxlength="500" placeholder="说明该映射的业务含义" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="draft.enabled"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
