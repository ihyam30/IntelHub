import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { resolveRouteRedirect } from './auth/route-policy'
import AdminLayout from './layouts/AdminLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/auth', component: () => import('./views/AuthView.vue'), meta: { guest: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: () => import('./views/ChatView.vue'), meta: { auth: true, title: '智能问答' } },
    { path: '/knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { auth: true, title: '知识库' } },
    { path: '/pipelines', component: () => import('./views/PipelinesView.vue'), meta: { auth: true, title: '入库流程' } },
    {
      path: '/admin',
      component: AdminLayout,
      meta: { auth: true, admin: true, adminLayout: true },
      children: [
        { path: '', redirect: '/admin/dashboard' },
        { path: 'dashboard', component: () => import('./views/admin/DashboardView.vue'), meta: { title: 'Dashboard' } },
        { path: 'knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { title: '知识库管理' } },
        { path: 'pipelines', component: () => import('./views/PipelinesView.vue'), meta: { title: '流水线管理' } },
        { path: 'tasks', component: () => import('./views/admin/IngestionTasksView.vue'), meta: { title: '流水线任务' } },
        { path: 'intents/tree', component: () => import('./views/admin/IntentAdminView.vue'), meta: { title: '意图树配置' } },
        { path: 'intents/list', component: () => import('./views/admin/IntentAdminView.vue'), meta: { title: '意图列表' } },
        { path: 'mappings', component: () => import('./views/admin/QueryTermMappingView.vue'), meta: { title: '关键词映射' } },
        { path: 'questions', component: () => import('./views/admin/SampleQuestionsView.vue'), meta: { title: '示例问题' } },
        { path: 'traces', component: () => import('./views/admin/TracesView.vue'), meta: { title: '链路追踪' } },
        { path: 'evaluation', component: () => import('./views/admin/RagEvaluationView.vue'), meta: { title: '问答评测' } },
        { path: 'users', component: () => import('./views/admin/UsersView.vue'), meta: { title: '用户管理' } },
        { path: 'models', component: () => import('./views/admin/ModelRoutesView.vue'), meta: { title: '模型路由' } },
        { path: 'settings', component: () => import('./views/admin/SystemSettingsView.vue'), meta: { title: '系统设置' } },
        { path: 'management', redirect: (to) => ({ path: ({ users: '/admin/users', models: '/admin/models', intents: '/admin/intents/tree', traces: '/admin/traces', settings: '/admin/settings' } as Record<string, string>)[String(to.query.tab)] || '/admin/dashboard' }) },
      ],
    },
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
