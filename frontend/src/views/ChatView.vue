<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { http, type Citation, type KnowledgeBase } from '../api'

type ClarificationOption = { id: string; name: string; description: string }
type ChatMessage = { id: string; role: 'user' | 'assistant'; content: string; citations?: Citation[]; status?: string; traceId?: string; modelId?: string; clarificationOptions?: ClarificationOption[] }
type Conversation = { id: string; knowledgeBaseId: string; title: string; summary: string; updatedAt: string }
const knowledgeBases = ref<KnowledgeBase[]>([])
const conversations = ref<Conversation[]>([])
const selectedKb = ref('')
const selectedConversation = ref('')
const selectedSummary = ref('')
const messages = ref<ChatMessage[]>([])
const question = ref('')
const sending = ref(false)
const scrollPane = ref<HTMLElement>()
const activeAssistant = ref<ChatMessage | null>(null)
let aborter: AbortController | null = null
const canSend = computed(() => !!selectedKb.value && !!question.value.trim() && !sending.value)

onMounted(async () => {
  try {
    knowledgeBases.value = await http.get('/knowledge-bases') as KnowledgeBase[]
    conversations.value = await http.get('/conversations') as Conversation[]
    selectedKb.value = knowledgeBases.value[0]?.id || ''
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载失败') }
})

async function selectConversation(item: Conversation) {
  selectedConversation.value = item.id
  selectedKb.value = item.knowledgeBaseId
  selectedSummary.value = item.summary || ''
  const rows = await http.get(`/conversations/${item.id}/messages`) as Array<{ id: string; role: 'user' | 'assistant'; content: string; citations: string }>
  messages.value = rows.map(row => ({ id: row.id, role: row.role, content: row.content, citations: parseCitations(row.citations) }))
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
</script>

<template>
  <div class="chat-layout">
    <aside class="conversation-rail">
      <div class="rail-header"><div><span class="section-kicker">CONVERSATIONS</span><h2>对话</h2></div><button class="square-action" aria-label="新建对话" @click="newConversation">＋</button></div>
      <el-button class="new-chat-button" @click="newConversation"><span>＋</span> 开始新对话</el-button>
      <div class="rail-section-label">最近对话</div>
      <div v-for="item in conversations" :key="item.id" class="conversation-item-row">
        <button class="conversation-item" :class="{ active: selectedConversation === item.id }" @click="selectConversation(item)">
          <span class="conversation-bubble">◌</span><span class="conversation-title">{{ item.title }}</span><span class="conversation-chevron">›</span>
        </button>
        <el-button class="conversation-delete" text title="删除会话" aria-label="删除会话" @click.stop="deleteConversation(item)">×</el-button>
      </div>
      <div v-if="!conversations.length" class="empty-rail">提问后，对话会出现在这里。</div>
      <div class="rail-tip"><div class="tip-icon">✳</div><strong>试试这样提问</strong><span>“请总结这份文档的关键要求，并标注出处。”</span></div>
    </aside>
    <section class="chat-main">
      <div class="chat-toolbar">
        <div class="chat-heading"><div class="chat-orb">✦</div><div><h1>知识问答</h1><p>回答来自您的文档，并附上可核验的引用</p></div></div>
        <div class="kb-picker"><span class="picker-label">知识库</span><el-select v-model="selectedKb" placeholder="选择知识库" size="large" :disabled="sending" no-data-text="请先创建知识库"><el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" /></el-select></div>
      </div>
      <div v-if="selectedSummary" class="conversation-memory"><strong>会话记忆</strong><span>{{ selectedSummary }}</span></div>
      <div ref="scrollPane" class="message-scroll">
        <div v-if="!messages.length" class="chat-welcome">
          <div class="welcome-eyebrow"><span></span> YOUR KNOWLEDGE ASSISTANT</div><h2>你好，{{ '今天想了解什么？' }}</h2><p>选择一个知识库，提出问题。我会检索相关文档并把依据一并展示给你。</p>
          <div v-if="!knowledgeBases.length" class="empty-kb-card"><div class="empty-kb-icon">▤</div><div><strong>还没有知识库</strong><span>先创建一个知识库并上传文档，再开始提问。</span></div><RouterLink to="/knowledge">前往知识库 <span>→</span></RouterLink></div>
          <div v-else class="suggestion-grid"><button @click="question = '请概括知识库中的主要内容，并列出对应来源。'"><span class="suggestion-icon violet">✧</span><strong>快速概览</strong><small>概括资料的主要内容</small><span class="suggestion-arrow">↗</span></button><button @click="question = '知识库中有哪些需要特别注意的规则或限制？'"><span class="suggestion-icon blue">⌕</span><strong>查找重点</strong><small>提取规则、要求和限制</small><span class="suggestion-arrow">↗</span></button><button @click="question = '请比较文档中的关键数据和时间节点。'"><span class="suggestion-icon amber">▤</span><strong>对比信息</strong><small>梳理数据与时间节点</small><span class="suggestion-arrow">↗</span></button></div>
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
              <div v-if="message.citations?.length" class="citation-panel"><div class="citation-heading"><span>↳</span> 检索到 {{ message.citations.length }} 条参考资料</div><details v-for="(citation, index) in message.citations" :key="citation.chunkId" class="citation-item"><summary><b>[{{ index + 1 }}]</b><span>{{ citation.filename }}</span><small>{{ citation.chunkIndex < 0 ? '关键词命中' : `片段 ${citation.chunkIndex + 1}` }}</small></summary><p>{{ citation.excerpt }}</p></details></div>
              <div v-if="message.traceId" class="answer-meta"><span>◉ {{ message.modelId }}</span><span>Trace {{ message.traceId.slice(0, 8) }}</span></div>
            </div>
          </article>
        </div>
      </div>
      <div class="composer-wrap">
        <div class="composer-box"><el-input v-model="question" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }" resize="none" placeholder="向知识库提问…" :disabled="!selectedKb || sending" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send" />
          <div class="composer-bottom"><div class="composer-hints"><span class="hint-spark">✦</span> 仅依据当前知识库内容回答 <span class="hint-separator">·</span> <kbd>⌘ ↵</kbd> 发送</div><el-button v-if="sending" class="send-button stop-button" circle @click="stop">■</el-button><el-button v-else class="send-button" type="primary" circle :disabled="!canSend" @click="send">↑</el-button></div>
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
.conversation-memory { display: flex; gap: 10px; align-items: baseline; padding: 9px 19px; color: #737b8d; font-size: 10px; background: #fafaff; border-bottom: 1px solid #eceef4; }
.conversation-memory strong { flex: none; color: #656bd0; }
.conversation-memory span { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; line-height: 1.5; }
.clarification-card { margin-top: 11px; padding: 12px; max-width: 620px; border: 1px solid #e6e7f4; border-radius: 10px; background: #fafaff; }
.clarification-options { display: flex; flex-wrap: wrap; gap: 8px; }
.clarification-options .el-button { margin: 0; }
.clarification-options small { color: #9299a9; font-size: 9px; }
@media (max-width: 620px) { .conversation-memory { padding: 8px 13px; } }
</style>
