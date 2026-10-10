<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import {
  Aim, ChatLineSquare, Coin, Connection, Cpu, Document, Grid, Histogram,
  Key, Operation, Search, Setting, User,
} from '@element-plus/icons-vue'

type NavItem = { label: string; path: string; icon: object }
type NavGroup = { label: string; items: NavItem[] }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const mobileOpen = ref(false)
const search = ref('')
const searchOpen = ref(false)
const openGroups = ref<Record<string, boolean>>({})

const groups: NavGroup[] = [
  { label: '概览', items: [{ label: 'Dashboard', path: '/admin/dashboard', icon: Grid }] },
  { label: '知识管理', items: [{ label: '知识库管理', path: '/admin/knowledge', icon: Coin }] },
  { label: '意图管理', items: [
    { label: '意图树配置', path: '/admin/intents/tree', icon: Connection },
    { label: '意图列表', path: '/admin/intents/list', icon: Aim },
  ] },
  { label: '数据通道', items: [
    { label: '流水线管理', path: '/admin/pipelines', icon: Operation },
    { label: '流水线任务', path: '/admin/tasks', icon: Document },
    { label: '关键词映射', path: '/admin/mappings', icon: Key },
  ] },
  { label: '质量与运维', items: [
    { label: '链路追踪', path: '/admin/traces', icon: Connection },
    { label: '问答评测', path: '/admin/evaluation', icon: Histogram },
    { label: '模型路由', path: '/admin/models', icon: Cpu },
  ] },
  { label: '设置', items: [
    { label: '用户管理', path: '/admin/users', icon: User },
    { label: '示例问题', path: '/admin/questions', icon: ChatLineSquare },
    { label: '系统设置', path: '/admin/settings', icon: Setting },
  ] },
]

const searchItems = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  const items = groups.flatMap(group => group.items.map(item => ({ ...item, group: group.label })))
  return keyword ? items.filter(item => `${item.label} ${item.group}`.toLocaleLowerCase().includes(keyword)) : items.slice(0, 6)
})

watch(() => route.path, (path) => {
  for (const group of groups) {
    if (group.items.some(item => path === item.path || path.startsWith(`${item.path}/`))) openGroups.value[group.label] = true
  }
  mobileOpen.value = false
}, { immediate: true })

function toggleGroup(label: string) { openGroups.value[label] = !openGroups.value[label] }
function isItemActive(item: NavItem) { return route.path === item.path || route.path.startsWith(`${item.path}/`) }
function navigate(path: string) { searchOpen.value = false; search.value = ''; mobileOpen.value = false; void router.push(path) }
function onGlobalKey(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); searchOpen.value = true; requestAnimationFrame(() => document.querySelector<HTMLInputElement>('.console-command-search')?.focus()) }
  if (event.key === 'Escape') { searchOpen.value = false; mobileOpen.value = false }
}

onMounted(() => window.addEventListener('keydown', onGlobalKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey))

async function signOut() {
  await auth.logout()
  await router.push('/auth')
}
</script>

<template>
  <div class="admin-shell ragent-console" :class="{ 'is-collapsed': collapsed, 'is-mobile-open': mobileOpen }">
    <button v-if="mobileOpen" class="console-sidebar-backdrop" aria-label="关闭导航" @click="mobileOpen = false" />
    <aside class="admin-sidebar console-sidebar">
      <RouterLink class="admin-brand console-brand" to="/admin/dashboard" title="IntelHub 控制台">
        <span class="admin-brand-mark">I</span>
        <span class="console-brand-copy"><strong>IntelHub 管理后台</strong><small>KNOWLEDGE CONSOLE</small></span>
      </RouterLink>
      <div class="admin-sidebar-label">导航</div>
      <nav class="admin-nav console-nav" aria-label="管理后台导航">
        <section v-for="group in groups" :key="group.label" class="console-nav-group">
          <button v-if="group.items.length > 1" class="console-nav-group-title" :class="{ 'is-open': openGroups[group.label] }" @click="toggleGroup(group.label)" :title="collapsed ? group.label : undefined">
            <span>{{ group.label }}</span><span class="console-nav-chevron">{{ openGroups[group.label] ? '⌄' : '›' }}</span>
          </button>
          <div v-show="group.items.length === 1 || openGroups[group.label] || collapsed" class="console-nav-items">
            <RouterLink v-for="item in group.items" :key="item.path" :to="item.path" class="console-nav-link" :class="{ 'is-active': isItemActive(item) }" :title="collapsed ? item.label : undefined" exact-active-class="is-exact-active">
              <span class="console-nav-icon"><el-icon><component :is="item.icon" /></el-icon></span>
              <span class="console-nav-label">{{ item.label }}</span>
            </RouterLink>
          </div>
        </section>
      </nav>

      <div class="admin-sidebar-bottom console-sidebar-bottom">
        <RouterLink class="admin-back-chat" to="/chat"><span>▢</span><span class="console-nav-label">返回聊天</span></RouterLink>
        <button class="console-collapse-button" @click="collapsed = !collapsed"><span>{{ collapsed ? '»' : '«' }}</span><span class="console-nav-label">{{ collapsed ? '展开侧栏' : '收起侧边栏' }}</span></button>
        <div class="admin-profile console-profile">
          <span class="admin-avatar">{{ auth.user?.displayName?.slice(0, 1) || 'A' }}</span>
          <span class="admin-profile-copy console-profile-copy"><strong>{{ auth.user?.displayName || auth.user?.username || '管理员' }}</strong><small>管理员</small></span>
          <el-dropdown trigger="click" @command="signOut">
            <button class="admin-profile-menu" aria-label="账户菜单">···</button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <main class="admin-main-column console-main">
      <header class="admin-topbar console-topbar">
        <div class="console-topbar-left">
          <button class="console-mobile-menu" aria-label="打开导航" @click="mobileOpen = !mobileOpen">☰</button>
          <div class="console-command-wrap">
            <el-icon><Search /></el-icon>
            <input v-model="search" class="console-command-search" placeholder="搜索管理功能…" autocomplete="off" @focus="searchOpen = true" @keydown.enter.prevent="searchItems[0] && navigate(searchItems[0].path)" />
            <kbd>Ctrl K</kbd>
            <div v-if="searchOpen" class="console-command-menu">
              <button v-for="item in searchItems" :key="item.path" @mousedown.prevent="navigate(item.path)">
                <span>{{ item.label }}</span><small>{{ item.group }}</small>
              </button>
              <div v-if="!searchItems.length" class="console-command-empty">没有匹配的管理页面</div>
            </div>
          </div>
        </div>
        <div class="admin-topbar-actions console-topbar-actions">
          <span class="admin-health"><i />系统运行中</span>
          <RouterLink to="/chat" class="admin-topbar-chat"><el-icon><ChatLineSquare /></el-icon>返回聊天</RouterLink>
          <el-dropdown trigger="click" @command="signOut">
            <button class="console-user-pill"><span class="admin-avatar small">{{ auth.user?.displayName?.slice(0, 1) || 'A' }}</span><span>{{ auth.user?.displayName || auth.user?.username || '管理员' }}</span><span>⌄</span></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
