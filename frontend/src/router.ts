import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { resolveRouteRedirect } from './auth/route-policy'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/auth', component: () => import('./views/AuthView.vue'), meta: { guest: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: () => import('./views/ChatView.vue'), meta: { auth: true, title: '智能问答' } },
    { path: '/knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { auth: true, title: '知识库' } },
    { path: '/pipelines', component: () => import('./views/PipelinesView.vue'), meta: { auth: true, title: '入库流程' } },
    { path: '/admin', component: () => import('./views/AdminView.vue'), meta: { auth: true, admin: true, title: '管理中心' } },
    { path: '/:pathMatch(.*)*', redirect: '/chat' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (auth.token && !auth.user) {
    try { await auth.refresh() } catch { auth.clear() }
  }
  return resolveRouteRedirect(to.meta, { token: auth.token, isAdmin: auth.isAdmin })
})

window.addEventListener('intelhub:auth-expired', () => { void router.push('/auth') })

export default router
