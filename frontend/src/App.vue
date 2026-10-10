<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isGuest = computed(() => route.meta.guest === true)
const isChat = computed(() => route.path === '/chat' || route.path.startsWith('/chat/'))
const isAdmin = computed(() => route.meta.adminLayout === true)
const title = computed(() => String(route.meta.title || '工作台'))

async function signOut() {
  await auth.logout()
  await router.push('/auth')
}
</script>

<template>
  <RouterView v-if="isGuest || isChat || isAdmin" />
  <div v-else class="member-shell">
    <aside class="member-sidebar">
      <RouterLink class="member-brand" to="/chat">
        <span class="member-brand-mark">I</span>
        <span><strong>IntelHub</strong><small>KNOWLEDGE WORKSPACE</small></span>
      </RouterLink>

      <div class="member-workspace"><i></i>个人知识空间</div>
      <div class="member-nav-caption">工作台</div>
      <nav class="member-nav">
        <RouterLink to="/chat"><span class="member-nav-icon">✦</span>智能问答</RouterLink>
        <RouterLink to="/knowledge"><span class="member-nav-icon">▤</span>知识库</RouterLink>
        <RouterLink to="/pipelines"><span class="member-nav-icon">⌘</span>入库流程</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/dashboard"><span class="member-nav-icon">▦</span>管理后台</RouterLink>
      </nav>

      <div class="member-sidebar-bottom">
        <div class="member-private-note"><span>⌑</span><div><strong>私有知识空间</strong><small>您的资料仅对您可见</small></div></div>
        <div class="member-profile">
          <span class="member-avatar">{{ auth.user?.displayName?.slice(0, 1) || 'U' }}</span>
          <span class="member-profile-copy"><strong>{{ auth.user?.displayName || auth.user?.username || '用户' }}</strong><small>{{ auth.isAdmin ? '管理员' : '个人账号' }}</small></span>
          <el-dropdown trigger="click" @command="signOut">
            <button class="member-more" aria-label="账户菜单">···</button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <main class="member-main">
      <header class="member-topbar">
        <div class="member-breadcrumb"><span>IntelHub</span><i>/</i><strong>{{ title }}</strong></div>
        <div class="member-topbar-right"><span class="member-health"><i></i>系统运行中</span><span class="member-top-avatar">{{ auth.user?.displayName?.slice(0, 1) || 'U' }}</span></div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
