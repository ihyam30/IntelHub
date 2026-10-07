<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '', displayName: '' })
const error = ref('')

async function submit() {
  error.value = ''
  if (mode.value === 'register' && form.password.length < 10) {
    error.value = '密码至少需要 10 位'
    return
  }
  try {
    if (mode.value === 'register') {
      await auth.register(form)
      mode.value = 'login'
      form.password = ''
      form.displayName = ''
      ElMessage.success('账号已创建，请登录')
      return
    }
    await auth.authenticate(form)
    ElMessage.success('欢迎回来')
    await router.push('/chat')
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '暂时无法登录'
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-art">
      <div class="art-grid"></div>
      <div class="art-orb orb-one"></div><div class="art-orb orb-two"></div>
      <div class="art-brand"><div class="brand-mark brand-mark-light"><span></span><span></span><span></span></div><span>IntelHub</span></div>
      <div class="art-copy"><div class="eyebrow light-eyebrow"><span></span> YOUR KNOWLEDGE, CONNECTED</div><h1>把散落的信息，<br /><em>变成清晰的答案。</em></h1><p>让团队知识有迹可循。上传文档、构建知识库，用可追溯的 AI 问答找到可靠答案。</p></div>
      <div class="art-footer"><span>检索 · 理解 · 回答</span><span>端到端知识工作流</span></div>
      <div class="floating-card card-top"><div class="mini-icon blue">✦</div><div><strong>语义检索</strong><small>从文档中找到相关内容</small></div><span class="tiny-pulse"></span></div>
      <div class="floating-card card-bottom"><div class="mini-icon green">✓</div><div><strong>来源可追溯</strong><small>每个回答都有引用依据</small></div></div>
    </div>
    <div class="auth-panel">
      <div class="auth-mobile-brand"><div class="brand-mark"><span></span><span></span><span></span></div><strong>IntelHub</strong></div>
      <div class="auth-card">
        <div class="auth-heading"><div class="eyebrow"><span></span>{{ mode === 'login' ? 'WELCOME BACK' : 'GET STARTED' }}</div><h2>{{ mode === 'login' ? '登录工作台' : '创建您的账号' }}</h2><p>{{ mode === 'login' ? '登录后继续访问您的知识空间。' : '注册一个账号，开始构建个人知识库。' }}</p></div>
        <el-form class="auth-form" @submit.prevent="submit">
          <el-form-item v-if="mode === 'register'" label="显示名称"><el-input v-model="form.displayName" placeholder="例如：林知远" maxlength="100" /></el-form-item>
          <el-form-item label="用户名"><el-input v-model="form.username" autocomplete="username" placeholder="输入用户名" minlength="3" maxlength="64" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="form.password" autocomplete="current-password" type="password" show-password placeholder="至少 10 位" @keyup.enter="submit" /></el-form-item>
          <div v-if="error" class="auth-error"><span>!</span>{{ error }}</div>
          <el-button class="auth-submit" type="primary" :loading="auth.loading" @click="submit">{{ mode === 'login' ? '登录' : '创建账号' }}<span>→</span></el-button>
        </el-form>
        <div class="auth-switch">{{ mode === 'login' ? '还没有账号？' : '已经注册过？' }}<button @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">{{ mode === 'login' ? '立即注册' : '返回登录' }}</button></div>
        <div class="auth-assurance"><span>⌑</span> 您的文档仅在授权的知识空间中检索</div>
      </div>
      <footer class="auth-legal">© 2026 IntelHub <span>安全 · 可追溯 · 高效</span></footer>
    </div>
  </div>
</template>
