<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { useRouter } from 'vue-router'
import { http, type Citation, type KnowledgeBase } from '../api'
import { useAuthStore } from '../stores/auth'

type ClarificationOption = { id: string; name: string; description: string }
type SampleQuestion = { id: string; title: string; question: string; description: string }
type ChatMessage = { id: string; role: 'user' | 'assistant'; content: string; citations?: Citation[]; status?: string; traceId?: string; modelId?: string; feedback?: number | null; feedbackEnabled?: boolean; feedbackSaving?: boolean; clarificationOptions?: ClarificationOption[] }
type Conversation = { id: string; knowledgeBaseId: string; title: string; summary: string; updatedAt: string }
const knowledgeBases = ref<KnowledgeBase[]>([])
const conversations = ref<Conversation[]>([])
const sampleQuestions = ref<SampleQuestion[]>([
  { id: 'summary', title: '内容总结', question: '请总结当前知识库中的主要内容，并列出对应来源。', description: '提炼资料中的关键主题和结论' },
  { id: 'rules', title: '查找重点', question: '知识库中有哪些需要特别注意的规则或限制？', description: '定位制度、要求和适用条件' },
  { id: 'compare', title: '对比信息', question: '请比较文档中的关键数据和时间节点。', description: '对照资料中的数据、版本和时间' },
])
const conversationSearch = ref('')
const selectedKb = ref('')
const selectedConversation = ref('')
const selectedSummary = ref('')
const messages = ref<ChatMessage[]>([])
const question = ref('')
const sending = ref(false)
const scrollPane = ref<HTMLElement>()
const activeAssistant = ref<ChatMessage | null>(null)
const auth = useAuthStore()
const router = useRouter()
let aborter: AbortController | null = null
const canSend = computed(() => !!selectedKb.value && !!question.value.trim() && !sending.value)
const visibleConversations = computed(() => {
  const term = conversationSearch.value.trim().toLocaleLowerCase()
  return [...conversations.value]
    .filter(item => !term || item.title.toLocaleLowerCase().includes(term))
    .sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
})
const conversationGroups = computed(() => {
  const now = new Date()
  const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const boundaries = [
    { title: '今天', from: midnight },
    { title: '最近 7 天', from: midnight - 6 * 24 * 60 * 60 * 1000 },
    { title: '最近 30 天', from: midnight - 29 * 24 * 60 * 60 * 1000 },
    { title: '更早', from: Number.NEGATIVE_INFINITY },
  ]
  return boundaries.map((group, index) => ({
    ...group,
    items: visibleConversations.value.filter(item => {
      const timestamp = new Date(item.updatedAt).getTime()
      const next = boundaries[index - 1]?.from ?? Number.POSITIVE_INFINITY
      return timestamp >= group.from && timestamp < next
    }),
  })).filter(group => group.items.length)
})
const selectedConversationTitle = computed(() => conversations.value.find(item => item.id === selectedConversation.value)?.title || '新对话')

function knowledgeBaseName(id?: string) {
  return id ? knowledgeBases.value.find(item => item.id === id)?.name : undefined
}

onMounted(async () => {
  try {
    knowledgeBases.value = await http.get('/knowledge-bases') as KnowledgeBase[]
    conversations.value = await http.get('/conversations') as Conversation[]
    sampleQuestions.value = await http.get('/sample-questions').catch(() => sampleQuestions.value) as SampleQuestion[]
    selectedKb.value = knowledgeBases.value[0]?.id || ''
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载失败') }
})

async function selectConversation(item: Conversation) {
  selectedConversation.value = item.id
  selectedKb.value = item.knowledgeBaseId
  selectedSummary.value = item.summary || ''
  const rows = await http.get(`/conversations/${item.id}/messages`) as Array<{ id: string; role: 'user' | 'assistant'; content: string; citations: string; modelId?: string; feedback?: number | null }>
  messages.value = rows.map(row => ({ id: row.id, role: row.role, content: row.content, citations: parseCitations(row.citations), modelId: row.modelId, feedback: row.feedback, feedbackEnabled: row.role === 'assistant' && row.modelId !== 'clarification' }))
}

function parseCitations(value: string | Citation[] | undefined): Citation[] {
  if (!value) return []
  if (Array.isArray(value)) return value
  try { return JSON.parse(value) as Citation[] } catch { return [] }
}

function newConversation() {
  selectedConversation.value = ''
  selectedSummary.value = ''
  messages.value = []
  question.value = ''
  void nextTick(() => document.querySelector<HTMLTextAreaElement>('.composer-box textarea')?.focus())
}

async function deleteConversation(item: Conversation) {
  try {
    await ElMessageBox.confirm(`删除会话「${item.title}」及其消息？`, '删除会话', { type: 'warning' })
    await http.delete(`/conversations/${item.id}`)
    conversations.value = conversations.value.filter(conversation => conversation.id !== item.id)
    if (selectedConversation.value === item.id) newConversation()
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除会话失败')
  }
}

async function send(clarificationAnswer: string | null = null) {
  if (!canSend.value) return
  const prompt = question.value.trim()
  question.value = ''
  const userMessage: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: prompt }
  const assistant: ChatMessage = { id: crypto.randomUUID(), role: 'assistant', content: '', citations: [], status: '正在连接' }
  messages.value.push(userMessage, assistant)
  activeAssistant.value = assistant
  sending.value = true
  aborter = new AbortController()
  await scrollBottom()
  try {
    const token = sessionStorage.getItem('intelhub-token') || ''
    const response = await fetch('/api/chat/stream', {
      method: 'POST', signal: aborter.signal,
      headers: { 'Content-Type': 'application/json', satoken: token, Accept: 'text/event-stream' },
      body: JSON.stringify({ knowledgeBaseId: selectedKb.value, conversationId: selectedConversation.value || null, question: prompt, clarificationAnswer }),
    })
    if (!response.ok || !response.body) {
      const detail = await response.json().catch(() => null)
      throw new Error(detail?.message || `问答请求失败 (${response.status})`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        handleEvent(block, assistant)
        boundary = buffer.indexOf('\n\n')
      }
      await scrollBottom()
    }
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      assistant.status = ''
      assistant.content ||= error instanceof Error ? error.message : '问答失败'
      ElMessage.error(assistant.content)
    }
  } finally {
    sending.value = false
    assistant.status = ''
    activeAssistant.value = null
    aborter = null
    conversations.value = await http.get('/conversations').catch(() => conversations.value) as Conversation[]
    selectedSummary.value = conversations.value.find(item => item.id === selectedConversation.value)?.summary || ''
  }
}

async function chooseClarification(message: ChatMessage, option: ClarificationOption) {
  if (sending.value) return
  message.clarificationOptions = []
  question.value = option.name
  await send(option.id)
}

async function submitFeedback(message: ChatMessage, vote: 1 | -1) {
  if (!message.feedbackEnabled || message.feedbackSaving) return
  const nextVote = message.feedback === vote ? null : vote
  message.feedbackSaving = true
  try {
    await http.post(`/conversations/messages/${message.id}/feedback`, { vote: nextVote })
    message.feedback = nextVote
    ElMessage.success(nextVote === null ? '已撤销评价' : nextVote === 1 ? '感谢反馈：有帮助' : '感谢反馈：没帮助')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '反馈提交失败')
  } finally {
    message.feedbackSaving = false
  }
}

function handleEvent(block: string, assistant: ChatMessage) {
  const event = block.match(/^event:\s*(.+)$/m)?.[1]?.trim() || 'message'
  const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')
  if (!data) return
  let payload: any
  try { payload = JSON.parse(data) } catch { payload = data }
  if (event === 'status') assistant.status = payload.message || (payload.position ? `排队中 · 前方 ${payload.position} 位` : payload.stage)
  if (event === 'intent') assistant.status = `识别意图：${payload.name}`
  if (event === 'clarification') {
    assistant.status = payload.message
    assistant.clarificationOptions = payload.options || []
    selectedConversation.value = payload.conversationId
  }
  if (event === 'citations') assistant.citations = parseCitations(payload as Citation[])
  if (event === 'token') assistant.content += payload.text || ''
  if (event === 'done') {
    assistant.traceId = payload.traceId
    assistant.modelId = payload.modelId
    if (payload.assistantMessageId) {
      assistant.id = payload.assistantMessageId
      assistant.feedbackEnabled = payload.modelId !== 'clarification'
      assistant.feedback = null
    }
    selectedConversation.value = payload.conversationId
  }
  if (event === 'error') assistant.status = payload.message || '生成失败'
}

async function scrollBottom() {
  await nextTick()
  if (scrollPane.value) scrollPane.value.scrollTop = scrollPane.value.scrollHeight
}

function renderMarkdown(content: string) {
  return DOMPurify.sanitize(marked.parse(content || '', { async: false }) as string)
}

function stop() { aborter?.abort() }

async function signOut() {
  await auth.logout()
  await router.push('/auth')
}

function openMobileSection(section: string) {
  void router.push(section)
}
</script>

<template>
  <div class="chat-layout">
    <aside class="conversation-rail">
      <div class="chat-brand-lockup"><span class="chat-brand-icon">✦</span><span><strong>IntelHub</strong><small>POWERED BY KNOWLEDGE</small></span></div>
      <section class="chat-quick-start">
        <div class="quick-start-heading"><span>快速开始</span><button @click="newConversation">新内容</button></div>
        <button class="quick-start-new" @click="newConversation"><span class="quick-start-plus">＋</span><span><strong>新建对话</strong><small>从空白开始</small></span></button>
        <div class="chat-rail-links"><RouterLink to="/knowledge">▤ <span>知识库管理</span></RouterLink><RouterLink to="/pipelines">⌘ <span>入库流程</span></RouterLink><RouterLink v-if="auth.isAdmin" to="/admin/dashboard">⚙ <span>管理后台</span></RouterLink></div>
      </section>
      <div class="rail-header"><div><span class="section-kicker">CONVERSATIONS</span><h2>对话记录</h2></div><button class="square-action" aria-label="新建对话" @click="newConversation">＋</button></div>
      <el-input v-model="conversationSearch" class="conversation-search" placeholder="搜索对话…" clearable />
      <div class="rail-section-label">最近对话 <span>{{ visibleConversations.length }}</span></div>
      <div class="conversation-list">
        <section v-for="group in conversationGroups" :key="group.title" class="conversation-group">
          <h3>{{ group.title }}</h3>
          <div v-for="item in group.items" :key="item.id" class="conversation-item-row">
            <button class="conversation-item" :class="{ active: selectedConversation === item.id }" @click="selectConversation(item)">
              <span class="conversation-bubble">◌</span><span class="conversation-title">{{ item.title }}</span><span class="conversation-chevron">›</span>
            </button>
            <el-button class="conversation-delete" text title="删除会话" aria-label="删除会话" @click.stop="deleteConversation(item)">×</el-button>
          </div>
        </section>
        <div v-if="!visibleConversations.length" class="empty-rail">
          <span class="empty-conversation-icon">▤</span>
          <strong>{{ conversations.length ? '没有匹配的对话' : '暂无对话记录' }}</strong>
          <small>{{ conversations.length ? '试试其他关键词。' : '提问后，对话会出现在这里。' }}</small>
        </div>
      </div>
      <div class="rail-tip"><div class="tip-icon">✳</div><strong>试试这样提问</strong><span>“请总结这份文档的关键要求，并标注出处。”</span></div>
      <div class="chat-account-row"><span class="avatar">{{ auth.user?.displayName?.slice(0, 1) || 'U' }}</span><span><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><small>{{ auth.isAdmin ? '管理员' : '个人账号' }}</small></span><el-dropdown trigger="click" @command="signOut"><button class="more-button" aria-label="账户菜单">···</button><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
    </aside>
    <section class="chat-main">
      <div class="chat-toolbar">
        <el-dropdown class="chat-mobile-menu" trigger="click" @command="openMobileSection"><button aria-label="打开导航">☰</button><template #dropdown><el-dropdown-menu><el-dropdown-item command="/knowledge">知识库管理</el-dropdown-item><el-dropdown-item command="/pipelines">入库流程</el-dropdown-item><el-dropdown-item v-if="auth.isAdmin" command="/admin/dashboard">管理后台</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        <div class="chat-heading"><div class="chat-orb">✦</div><div><h1>{{ selectedConversationTitle }}</h1><p>{{ selectedConversation ? '继续查看与知识库的问答记录' : '回答来自您的文档，并附上可核验的引用' }}</p></div></div>
        <div class="kb-picker"><span class="picker-label">知识库</span><el-select v-model="selectedKb" placeholder="选择知识库" size="large" :disabled="sending" no-data-text="请先创建知识库"><el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" /></el-select></div>
      </div>
      <div v-if="selectedSummary" class="conversation-memory"><strong>会话记忆</strong><span>{{ selectedSummary }}</span></div>
      <div ref="scrollPane" class="message-scroll">
        <div v-if="!messages.length" class="chat-welcome">
          <div class="welcome-product-badge"><span>✦</span> RAG 智能问答</div><h2>把问题变成<em>清晰答案</em></h2><p>结构化提问、知识检索与深度思考，一次对话给出可核验的依据。</p>
          <div v-if="!knowledgeBases.length" class="empty-kb-card"><div class="empty-kb-icon">▤</div><div><strong>还没有知识库</strong><span>先创建一个知识库并上传文档，再开始提问。</span></div><RouterLink to="/knowledge">前往知识库 <span>→</span></RouterLink></div>
          <div v-else class="suggestion-grid"><button v-for="(sample, index) in sampleQuestions" :key="sample.id" @click="question = sample.question"><span class="suggestion-icon" :class="['violet','blue','amber'][index % 3]">{{ ['✧','⌕','▤'][index % 3] }}</span><strong>{{ sample.title }}</strong><small>{{ sample.description || sample.question }}</small><span class="suggestion-arrow">↗</span></button></div>
        </div>
        <div v-else class="message-list">
          <article v-for="message in messages" :key="message.id" class="message-row" :class="message.role">
            <div class="message-avatar" :class="message.role">{{ message.role === 'assistant' ? '✦' : '我' }}</div>
            <div class="message-body">
              <div class="message-author">{{ message.role === 'assistant' ? 'IntelHub 助手' : '您' }}<span v-if="message.status" class="typing-status"><i></i>{{ message.status }}</span></div>
              <div v-if="message.content" class="markdown-body" v-html="renderMarkdown(message.content)"></div>
              <div v-if="message.clarificationOptions?.length" class="clarification-card">
                <div class="clarification-options"><el-button v-for="option in message.clarificationOptions" :key="option.id" size="small" @click="chooseClarification(message, option)">{{ option.name }}<small v-if="option.description"> · {{ option.description }}</small></el-button></div>
              </div>
              <div v-else-if="message.role === 'assistant' && sending" class="typing-dots"><i></i><i></i><i></i></div>
              <div v-if="message.citations?.length" class="citation-panel"><div class="citation-heading"><span>↳</span> 检索到 {{ message.citations.length }} 条参考资料</div><details v-for="(citation, index) in message.citations" :key="citation.chunkId" class="citation-item"><summary><b>[{{ index + 1 }}]</b><span>{{ citation.filename }}</span><small v-if="knowledgeBaseName(citation.knowledgeBaseId)">{{ knowledgeBaseName(citation.knowledgeBaseId) }}</small><small>{{ citation.chunkIndex < 0 ? '关键词命中' : `片段 ${citation.chunkIndex + 1}` }}</small></summary><p>{{ citation.excerpt }}</p></details></div>
              <div v-if="message.traceId" class="answer-meta"><span>◉ {{ message.modelId }}</span><span>Trace {{ message.traceId.slice(0, 8) }}</span></div>
              <div v-if="message.feedbackEnabled && message.content" class="answer-feedback" aria-label="评价回答">
                <span>这个回答有帮助吗？</span>
                <el-button text size="small" :disabled="message.feedbackSaving" :class="{ selected: message.feedback === 1 }" aria-label="有帮助" @click="submitFeedback(message, 1)">👍 有帮助</el-button>
                <el-button text size="small" :disabled="message.feedbackSaving" :class="{ selected: message.feedback === -1 }" aria-label="没帮助" @click="submitFeedback(message, -1)">👎 没帮助</el-button>
              </div>
            </div>
          </article>
        </div>
      </div>
      <div class="composer-wrap">
        <div class="composer-box"><el-input v-model="question" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }" resize="none" placeholder="向知识库提问…" :disabled="!selectedKb || sending" @keydown.ctrl.enter.prevent="send(null)" @keydown.meta.enter.prevent="send(null)" />
          <div class="composer-bottom"><div class="composer-hints"><span class="hint-spark">✦</span> 仅依据当前知识库内容回答 <span class="hint-separator">·</span> <kbd>⌘ ↵</kbd> 发送</div><el-button v-if="sending" class="send-button stop-button" circle @click="stop">■</el-button><el-button v-else class="send-button" type="primary" circle :disabled="!canSend" @click="send(null)">↑</el-button></div>
        </div><div class="composer-disclaimer">AI 生成内容可能存在偏差，请结合引用来源核验重要信息。</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.conversation-item-row { display: flex; align-items: center; gap: 2px; }
.conversation-item-row .conversation-item { min-width: 0; flex: 1; }
.conversation-item-row .conversation-delete { flex: none; opacity: 0; color: #a0a6b7; padding: 4px; }
.conversation-item-row:hover .conversation-delete, .conversation-item-row:focus-within .conversation-delete { opacity: 1; }
.conversation-group { margin: 0 0 12px; }
.conversation-group h3 { margin: 0; padding: 5px 5px 6px; color: #9ba4b4; font-size: 9px; font-weight: 600; }
.empty-rail { min-height: 160px; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 7px; padding: 16px 10px; text-align: center; color: #a0a7b4; }
.empty-conversation-icon { color: #b6bac3; font-size: 30px; line-height: 1; }
.empty-rail strong { color: #8f97a6; font-size: 11px; font-weight: 500; }
.empty-rail small { color: #a4aaba; font-size: 9px; line-height: 1.5; }
.conversation-memory { display: flex; gap: 10px; align-items: baseline; padding: 9px 19px; color: #737b8d; font-size: 10px; background: #fafaff; border-bottom: 1px solid #eceef4; }
.conversation-memory strong { flex: none; color: #656bd0; }
.conversation-memory span { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; line-height: 1.5; }
.clarification-card { margin-top: 11px; padding: 12px; max-width: 620px; border: 1px solid #e6e7f4; border-radius: 10px; background: #fafaff; }
.clarification-options { display: flex; flex-wrap: wrap; gap: 8px; }
.clarification-options .el-button { margin: 0; }
.clarification-options small { color: #9299a9; font-size: 9px; }
.answer-feedback { display: flex; align-items: center; gap: 4px; margin-top: 8px; color: #a0a6b7; font-size: 10px; }
.answer-feedback .el-button { min-height: 25px; padding: 3px 7px; color: #8991a3; font-size: 10px; }
.answer-feedback .el-button:hover, .answer-feedback .el-button.selected { color: #5964d5; background: #f1f1ff; }
@media (max-width: 620px) { .conversation-memory { padding: 8px 13px; } }
</style>
