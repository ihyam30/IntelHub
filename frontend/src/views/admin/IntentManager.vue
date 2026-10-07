<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api'

const rows = ref<any[]>([])
const loading = ref(false)
const editingId = ref('')
const draft = ref({ name: '', description: '', keywords: '', promptHint: '' })

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { rows.value = await http.get('/intents') as any[] }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载意图失败') }
  finally { loading.value = false }
}

async function create() {
  const name = await ElMessageBox.prompt('输入意图名称', '添加意图', { inputValue: '新意图', inputPattern: /.+/, inputErrorMessage: '名称不能为空' }).catch(() => null)
  if (!name) return
  const keywords = await ElMessageBox.prompt('输入关键词，以逗号分隔', '配置关键词', { inputValue: '关键词一，关键词二', inputPattern: /.+/, inputErrorMessage: '至少输入一个关键词' }).catch(() => null)
  if (!keywords) return
  try {
    await http.post('/intents', { name: name.value, description: '', keywords: keywords.value.split(/[，,]/).map(value => value.trim()).filter(Boolean), promptHint: '', enabled: true })
    await load()
    ElMessage.success('意图已创建')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '创建失败') }
}

async function toggle(item: any) {
  try {
    await http.put(`/intents/${item.id}`, { name: item.name, description: item.description, keywords: item.keywords, promptHint: item.promptHint, enabled: !item.enabled })
    item.enabled = !item.enabled
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新状态失败') }
}

function beginEdit(item: any) {
  editingId.value = item.id
  draft.value = { name: item.name, description: item.description || '', keywords: (item.keywords || []).join('，'), promptHint: item.promptHint || '' }
}

async function saveEdit(item: any) {
  const keywords = draft.value.keywords.split(/[，,]/).map(value => value.trim()).filter(Boolean)
  if (!draft.value.name.trim() || keywords.length === 0) {
    ElMessage.warning('请填写意图名称和至少一个关键词')
    return
  }
  try {
    await http.put(`/intents/${item.id}`, { name: draft.value.name.trim(), description: draft.value.description.trim(), keywords, promptHint: draft.value.promptHint.trim(), enabled: item.enabled })
    editingId.value = ''
    await load()
    ElMessage.success('意图配置已保存')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存意图失败') }
}

async function remove(item: any) {
  try {
    await ElMessageBox.confirm(`删除意图「${item.name}」？`, '删除意图', { type: 'warning' })
    await http.delete(`/intents/${item.id}`)
    rows.value = rows.value.filter(row => row.id !== item.id)
    if (editingId.value === item.id) editingId.value = ''
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}
</script>

<template>
  <div class="intent-manager">
    <div class="intent-manager-top"><div><strong>意图分类与引导规则</strong><small>根据关键词识别问题类型；未命中时继续通用检索。</small></div><el-button type="primary" @click="create">＋ 添加意图</el-button></div>
    <div v-loading="loading" class="intent-manager-list">
      <div v-for="item in rows" :key="item.id" class="intent-manager-row">
        <div class="intent-manager-icon">⌘</div><div class="intent-manager-body"><strong>{{ item.name }}</strong><small>{{ item.description || '暂无描述' }}</small><div class="intent-manager-tags"><span v-for="word in item.keywords" :key="word">{{ word }}</span></div><p v-if="item.promptHint">回答提示：{{ item.promptHint }}</p></div>
        <el-tag :type="item.enabled ? 'success' : 'info'">{{ item.enabled ? '启用' : '停用' }}</el-tag><el-button text @click="toggle(item)">{{ item.enabled ? '停用' : '启用' }}</el-button><el-button v-if="editingId !== item.id" text @click="beginEdit(item)">编辑</el-button><el-button text type="danger" @click="remove(item)">删除</el-button>
        <div v-if="editingId === item.id" class="intent-edit-fields">
          <el-input v-model="draft.name" aria-label="意图名称" placeholder="意图名称" />
          <el-input v-model="draft.description" aria-label="意图说明" placeholder="意图说明" />
          <el-input v-model="draft.keywords" aria-label="关键词" placeholder="关键词，以逗号分隔" />
          <el-input v-model="draft.promptHint" aria-label="提示词" placeholder="回答提示词" />
          <div class="intent-edit-actions"><el-button size="small" type="primary" @click="saveEdit(item)">保存</el-button><el-button size="small" @click="editingId = ''">取消</el-button></div>
        </div>
      </div>
      <div v-if="!rows.length && !loading" class="runs-empty">暂无意图分类。</div>
    </div>
    <div class="setting-hint"><span>ⓘ</span> 当前采用可配置关键词匹配作为意图路由，分类结果会写入问答 Trace 并作为生成提示。</div>
  </div>
</template>

<style scoped>
.intent-edit-fields { flex: 0 0 100%; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 10px 0 2px 38px; }
.intent-manager-row { flex-wrap: wrap; }
.intent-edit-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; }
@media (max-width: 620px) { .intent-edit-fields { grid-template-columns: 1fr; padding-left: 0; } .intent-edit-actions { grid-column: 1; } }
</style>
