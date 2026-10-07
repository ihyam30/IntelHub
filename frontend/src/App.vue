<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isAuth = computed(() => route.meta.guest === true)
const pageTitle = computed(() => String(route.meta.title || '知识工作台'))

async function signOut() {
  await auth.logout()
  await router.push('/auth')
}
</script>

<template>
  <RouterView v-if="isAuth" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand-lockup">
        <div class="brand-mark"><span></span><span></span><span></span></div>
        <div><strong>IntelHub</strong><small>KNOWLEDGE OS</small></div>
      </div>
      <div class="workspace-tag"><span class="status-dot"></span> PERSONAL WORKSPACE</div>
      <div class="nav-label">工作台</div>
      <nav class="primary-nav">
        <RouterLink to="/chat"><span class="nav-icon">✳</span><span>智能问答</span><kbd>⌘ 1</kbd></RouterLink>
        <RouterLink to="/knowledge"><span class="nav-icon">▤</span><span>知识库</span><kbd>⌘ 2</kbd></RouterLink>
        <RouterLink to="/pipelines"><span class="nav-icon">⌘</span><span>入库流程</span><kbd>⌘ 3</kbd></RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin"><span class="nav-icon">◫</span><span>管理中心</span><span class="admin-pill">ADMIN</span></RouterLink>
      </nav>
      <div class="sidebar-foot">
        <div class="secure-note"><span>⌑</span><div><strong>私有知识空间</strong><small>您的文档仅对您可见</small></div></div>
        <div class="profile-row">
          <div class="avatar">{{ auth.user?.displayName?.slice(0, 1) || 'U' }}</div>
          <div class="profile-copy"><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><small>{{ auth.isAdmin ? '管理员' : '个人账号' }}</small></div>
          <el-dropdown trigger="click" @command="signOut">
            <button class="more-button" aria-label="用户菜单">···</button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </div>
    </aside>
    <main class="main-column">
      <header class="topbar">
        <div class="breadcrumb"><span>IntelHub</span><i>/</i><strong>{{ pageTitle }}</strong></div>
        <div class="topbar-right"><span class="connection"><span class="status-dot"></span> 系统运行中</span><div class="top-avatar">{{ auth.user?.displayName?.slice(0, 1) || 'U' }}</div></div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
