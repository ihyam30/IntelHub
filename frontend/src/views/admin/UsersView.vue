<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api'
import { useAuthStore } from '../../stores/auth'

type User = { id: string; username: string; displayName: string; role: 'USER' | 'ADMIN'; enabled: boolean; createdAt: string }
const auth = useAuthStore()
const rows = ref<User[]>([])
const loading = ref(false)
const saving = ref(false)
const search = ref('')
const appliedSearch = ref('')
const dialog = ref(false)
const editingId = ref('')
const draft = ref({ username: '', displayName: '', password: '', role: 'USER' as 'USER' | 'ADMIN' })
const stats = computed(() => ({ total: rows.value.length, enabled: rows.value.filter(row => row.enabled).length, admins: rows.value.filter(row => row.role === 'ADMIN' && row.enabled).length }))

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { rows.value = await http.get('/admin/users', { params: { query: appliedSearch.value } }) as User[] }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '用户列表加载失败') }
  finally { loading.value = false }
}

function searchUsers() { appliedSearch.value = search.value.trim(); void load() }

function openCreate() {
  editingId.value = ''
  draft.value = { username: '', displayName: '', password: '', role: 'USER' }
  dialog.value = true
}

function openEdit(user: User) {
  editingId.value = user.id
  draft.value = { username: user.username, displayName: user.displayName || '', password: '', role: user.role }
  dialog.value = true
}

async function save() {
  const displayName = draft.value.displayName.trim()
  if (!displayName) { ElMessage.warning('请填写显示名称'); return }
  saving.value = true
  try {
    if (editingId.value) {
      await http.put(`/admin/users/${editingId.value}`, { displayName, role: draft.value.role })
      ElMessage.success('用户信息已更新')
    } else {
      if (!draft.value.username.trim() || !draft.value.password) { ElMessage.warning('请填写用户名和初始密码'); return }
      await http.post('/admin/users', { username: draft.value.username.trim(), displayName, password: draft.value.password, role: draft.value.role })
      ElMessage.success('用户已创建')
    }
    dialog.value = false
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存用户失败') }
  finally { saving.value = false }
}

async function toggle(user: User) {
  try {
    await http.patch(`/admin/users/${user.id}/enabled`, { enabled: !user.enabled })
    user.enabled = !user.enabled
    ElMessage.success(user.enabled ? '用户已启用' : '用户已停用')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新状态失败') }
}

async function remove(user: User) {
  try {
    await ElMessageBox.confirm(`删除“${user.username}”会停用登录并匿名化账号资料，问答 Trace 和知识库归属记录会保留。继续吗？`, '删除用户', { type: 'warning', confirmButtonText: '确认删除' })
  } catch { return }
  try { await http.delete(`/admin/users/${user.id}`); await load(); ElMessage.success('用户已删除并匿名化') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除用户失败') }
}

function date(value: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
</script>

<template>
  <section class="page-content console-admin-page users-admin-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 设置 <i>/</i> 用户管理</div><h1>用户管理</h1><p>管理登录账号、角色和访问状态。删除账号会匿名化并停用，保留知识库与审计记录的关联。</p></div>
      <div class="console-heading-actions"><el-input v-model="search" class="users-search" placeholder="搜索用户名或显示名称" clearable @keyup.enter="searchUsers" @clear="searchUsers" /><el-button @click="searchUsers">⌕ 搜索</el-button><el-button @click="load">⟳ 刷新</el-button><el-button type="primary" @click="openCreate">＋ 新增用户</el-button></div>
    </div>
    <div class="intent-metric-grid users-metrics"><article><span>当前结果</span><strong>{{ stats.total }}</strong></article><article><span>启用账号</span><strong>{{ stats.enabled }}</strong></article><article><span>管理员</span><strong>{{ stats.admins }}</strong></article></div>
    <section class="console-card users-list-card">
      <header class="console-card-heading"><div><h2>账号与角色</h2><p>用户名不可变；管理员角色需谨慎授予。停用会立即撤销该用户的登录会话。</p></div></header>
      <el-table v-loading="loading" :data="rows" row-key="id" class="task-table users-table" empty-text="没有匹配的用户">
        <el-table-column label="用户" min-width="200"><template #default="scope"><div class="user-table-person"><span class="user-table-avatar" :class="scope.row.role.toLowerCase()">{{ (scope.row.displayName || scope.row.username).slice(0,1) }}</span><span><strong>{{ scope.row.displayName || scope.row.username }}</strong><small>{{ scope.row.username }}</small></span></div></template></el-table-column>
        <el-table-column label="角色" width="120"><template #default="scope"><el-tag size="small" :type="scope.row.role === 'ADMIN' ? 'warning' : 'info'">{{ scope.row.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="scope"><span class="user-status"><i :class="scope.row.enabled && !scope.row.username.startsWith('deleted-') ? 'online' : ''"></i>{{ scope.row.username.startsWith('deleted-') ? '已删除' : scope.row.enabled ? '正常' : '已停用' }}</span></template></el-table-column>
        <el-table-column label="创建时间" min-width="180"><template #default="scope">{{ date(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" min-width="220" fixed="right"><template #default="scope"><el-button v-if="!scope.row.username.startsWith('deleted-')" link :disabled="scope.row.id === auth.user?.id" @click="toggle(scope.row)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button v-if="!scope.row.username.startsWith('deleted-')" link @click="openEdit(scope.row)">编辑</el-button><el-button v-if="!scope.row.username.startsWith('deleted-')" link type="danger" :disabled="scope.row.id === auth.user?.id" @click="remove(scope.row)">删除</el-button><small v-else class="user-deleted-note">资料已匿名化</small></template></el-table-column>
      </el-table>
      <footer class="mapping-list-foot">当前接口最多返回 200 个匹配账号</footer>
    </section>
    <el-dialog v-model="dialog" :title="editingId ? '编辑用户' : '新增用户'" width="min(560px,92vw)" :close-on-click-modal="false">
      <el-form label-position="top" class="mapping-edit-form">
        <el-form-item v-if="!editingId" label="用户名" required><el-input v-model="draft.username" maxlength="64" placeholder="3–64 位字母、数字或 ._-" /></el-form-item>
        <el-form-item v-else label="用户名"><el-input :model-value="draft.username" disabled /></el-form-item>
        <el-form-item v-if="!editingId" label="初始密码" required><el-input v-model="draft.password" type="password" show-password minlength="10" maxlength="128" placeholder="至少 10 位" /></el-form-item>
        <el-form-item label="显示名称" required><el-input v-model="draft.displayName" maxlength="100" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="draft.role"><el-option label="普通用户" value="USER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item>
        <p v-if="!editingId" class="user-create-note">初始密码将以 BCrypt 哈希保存，创建后用户可使用用户名和密码登录。</p>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
