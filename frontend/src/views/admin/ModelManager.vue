<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '../../api'

const models = ref<any[]>([])
const tools = ref<string[]>([])
const rerank = ref({ configured: false, provider: 'Cohere-compatible', model: '未配置' })
const embedding = ref({ configured: false, provider: '本地确定性向量', model: 'local-deterministic', dimension: 384, mode: 'LOCAL_FALLBACK' })
const loading = ref(false)

function priorityLabel(model: any) {
  if (model.id === 'primary') return '主模型 · 优先级 1'
  if (model.id === 'fallback') return `备用模型 · 优先级 ${model.priority}`
  return '本地演示回退'
}

onMounted(async () => {
  loading.value = true
  try {
    const [modelList, toolList, rerankStatus, embeddingStatus] = await Promise.all([
      request<any[]>('/admin/models'),
      request<string[]>('/admin/tools').catch(() => []),
      request<typeof rerank.value>('/admin/rerank').catch(() => rerank.value),
      request<typeof embedding.value>('/admin/embedding').catch(() => embedding.value),
    ])
    models.value = modelList
    tools.value = toolList
    rerank.value = rerankStatus
    embedding.value = embeddingStatus
  }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '模型状态加载失败') }
  finally { loading.value = false }
})
</script>

<template>
  <div v-loading="loading" class="model-manager">
    <div class="model-intro"><span class="model-orbit">✳</span><div><strong>模型路由与熔断状态</strong><p>候选模型按主备顺序尝试。密钥由服务端环境变量提供，不会通过管理界面返回。</p></div></div>
    <div v-if="models.length" class="model-health-list"><div v-for="model in models" :key="model.id" class="model-health-card"><div class="model-provider-icon">{{ model.id === 'local-demo' ? '⌂' : '✦' }}</div><div class="model-provider-copy"><strong>{{ model.id }} · {{ model.model }}</strong><small>{{ priorityLabel(model) }} · 流式问答</small></div><el-tag :type="model.state === 'CLOSED' ? 'success' : model.state === 'OPEN' ? 'danger' : 'warning'">{{ model.state }}</el-tag><div class="model-failures">{{ model.failures }}<small>连续失败</small></div></div></div>
    <div class="model-integration-row"><strong>MCP 工具</strong><el-tag v-for="tool in tools" :key="tool" size="small" effect="plain">{{ tool }}</el-tag><span v-if="!tools.length">未连接到 MCP 服务</span></div>
    <div class="model-integration-row"><strong>Embedding</strong><el-tag :type="embedding.configured ? 'success' : 'info'">{{ embedding.configured ? '远端已配置' : '本地回退' }}</el-tag><span>{{ embedding.provider }} · {{ embedding.model }} · {{ embedding.dimension }} 维</span></div>
    <div class="model-integration-row"><strong>Rerank 精排</strong><el-tag :type="rerank.configured ? 'success' : 'info'">{{ rerank.configured ? '已配置' : 'RRF 回退' }}</el-tag><span>{{ rerank.provider }} · {{ rerank.model }}</span></div>
    <div class="setting-hint"><span>ⓘ</span> Chat 显示 Redis 共享熔断状态；Embedding 远端调用失败时回退到本地向量，Rerank 不可用时保留 RRF 排序。</div>
  </div>
</template>

<style scoped>
.model-integration-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;border-top:1px solid #f0f1f5;padding:12px 3px;color:#858c9c;font-size:9px}
.model-integration-row strong{min-width:94px;color:#636b7d}
</style>
