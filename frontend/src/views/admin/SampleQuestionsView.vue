<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api'

type SampleQuestion = { id: string; title: string; question: string; description: string; enabled: boolean; sortOrder: number; updatedAt: string }
const rows = ref<SampleQuestion[]>([])
const loading = ref(false)
const saving = ref(false)
const search = ref('')
const dialog = ref(false)
const editingId = ref('')
const draft = ref({ title: '', question: '', description: '', enabled: true })
const filteredRows = computed(() => {
  const term = search.value.trim().toLocaleLowerCase()
  return term ? rows.value.filter(row => `${row.title} ${row.question} ${row.description}`.toLocaleLowerCase().includes(term)) : rows.value
})
const enabledCount = computed(() => rows.value.filter(row => row.enabled).length)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { rows.value = await http.get('/sample-questions/admin') as SampleQuestion[] }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '示例问题加载失败') }
  finally { loading.value = false }
}

function openCreate() {
  editingId.value = ''
  draft.value = { title: '', question: '', description: '', enabled: true }
  dialog.value = true
}

function openEdit(row: SampleQuestion) {
  editingId.value = row.id
  draft.value = { title: row.title, question: row.question, description: row.description || '', enabled: row.enabled }
  dialog.value = true
}

async function save() {
  const payload = { title: draft.value.title.trim(), question: draft.value.question.trim(), description: draft.value.description.trim(), enabled: draft.value.enabled }
  if (!payload.title || !payload.question) { ElMessage.warning('请填写示例标题和问题内容'); return }
  saving.value = true
  try {
    if (editingId.value) await http.put(`/sample-questions/${editingId.value}`, payload)
    else await http.post('/sample-questions', payload)
    dialog.value = false
    await load()
    ElMessage.success(editingId.value ? '示例问题已更新' : '示例问题已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存示例问题失败') }
  finally { saving.value = false }
}

async function toggle(row: SampleQuestion) {
  try {
    const updated = await http.put(`/sample-questions/${row.id}`, { title: row.title, question: row.question, description: row.description, enabled: !row.enabled }) as SampleQuestion
    rows.value = rows.value.map(item => item.id === row.id ? updated : item)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新状态失败') }
}

async function remove(row: SampleQuestion) {
  try { await ElMessageBox.confirm(`删除示例“${row.title}”？`, '删除示例问题', { type: 'warning' }) }
  catch { return }
  try { await http.delete(`/sample-questions/${row.id}`); rows.value = rows.value.filter(item => item.id !== row.id); ElMessage.success('示例问题已删除') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除示例问题失败') }
}
</script>

<template>
  <section class="page-content console-admin-page sample-question-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 设置 <i>/</i> 示例问题</div><h1>示例问题管理</h1><p>维护问答首页的推荐问题，帮助用户快速开始检索。</p></div>
      <div class="console-heading-actions"><el-button @click="load">⟳ 刷新</el-button><el-button type="primary" @click="openCreate">＋ 新增示例</el-button></div>
    </div>
    <div class="intent-metric-grid sample-metrics"><article><span>示例总数</span><strong>{{ rows.length }}</strong></article><article><span>首页展示</span><strong>{{ enabledCount }}</strong></article><article><span>已停用</span><strong>{{ rows.length - enabledCount }}</strong></article></div>
    <section class="console-card sample-list-card">
      <header class="console-card-heading"><div><h2>推荐问题</h2><p>启用的问题会显示在问答首页；用户点击后可继续编辑再发送。</p></div><el-input v-model="search" class="sample-search" placeholder="搜索标题、描述或问题" clearable /></header>
      <el-table v-loading="loading" :data="filteredRows" row-key="id" class="task-table sample-table" empty-text="暂无示例问题">
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column label="推荐问题" min-width="330"><template #default="scope"><div class="sample-question-copy"><strong>{{ scope.row.question }}</strong><small>{{ scope.row.description || '—' }}</small></div></template></el-table-column>
        <el-table-column prop="sortOrder" label="顺序" width="80" />
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag size="small" :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '展示中' : '已停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right"><template #default="scope"><el-button link @click="toggle(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button link @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
      </el-table>
      <footer class="mapping-list-foot">共 {{ filteredRows.length }} 条示例</footer>
    </section>
    <el-dialog v-model="dialog" :title="editingId ? '编辑示例问题' : '新增示例问题'" width="min(640px,92vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="mapping-edit-form">
        <el-form-item label="标题" required><el-input v-model="draft.title" maxlength="120" placeholder="例如：内容总结" /></el-form-item>
        <el-form-item label="推荐问题" required><el-input v-model="draft.question" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="用户点击后填入聊天输入框的问题" /></el-form-item>
        <el-form-item label="简短说明"><el-input v-model="draft.description" maxlength="500" placeholder="展示在首页卡片上的辅助说明" /></el-form-item>
        <el-form-item label="首页展示"><el-select v-model="draft.enabled"><el-option label="展示" :value="true" /><el-option label="停用" :value="false" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
