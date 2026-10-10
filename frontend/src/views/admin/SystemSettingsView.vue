<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '../../api'

type Settings = { maxConcurrent: number; perUserRequestsPerMinute: number; maxWaitSeconds: number }
const settings = ref<Settings>({ maxConcurrent: 4, perUserRequestsPerMinute: 20, maxWaitSeconds: 30 })
const loading = ref(false)
const saving = ref(false)
const savedAt = ref('')

onMounted(() => { void load() })

async function load() {
  loading.value = true
  try { settings.value = await http.get('/admin/settings') as Settings; savedAt.value = new Date().toLocaleTimeString('zh-CN') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '系统设置加载失败') }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    settings.value = await http.put('/admin/settings', { maxConcurrent: settings.value.maxConcurrent }) as Settings
    savedAt.value = new Date().toLocaleTimeString('zh-CN')
    ElMessage.success('共享并发上限已更新')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '设置保存失败') }
  finally { saving.value = false }
}
</script>

<template>
  <section class="page-content console-admin-page system-settings-page" v-loading="loading">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 设置 <i>/</i> 系统设置</div><h1>系统设置</h1><p>展示并调整 IntelHub 当前支持的运行参数。只能在服务端配置的项目会保持只读。</p></div>
      <div class="console-heading-actions"><span v-if="savedAt" class="console-updated"><i></i>读取于 {{ savedAt }}</span><el-button @click="load">⟳ 刷新</el-button></div>
    </div>
    <section class="console-card setting-group-card">
      <header class="console-card-heading"><div><h2>全局问答限流</h2><p>跨后端实例共享并发许可与排队策略</p></div></header>
      <div class="settings-value-grid"><article><small>最大并发问答</small><el-input-number v-model="settings.maxConcurrent" :min="1" :max="64" /><p>可修改；Redis 共享并发上限，保存后立即生效。</p></article><article><small>单用户请求上限</small><strong>{{ settings.perUserRequestsPerMinute }} 次 / 分钟</strong><p>当前服务固定配额，展示为只读。</p></article><article><small>最大排队时间</small><strong>{{ settings.maxWaitSeconds }} 秒</strong><p>超过上限后问答请求会返回超时事件。</p></article></div>
      <footer class="settings-save-row"><span>保存后会同步到共享 Redis 设置</span><el-button type="primary" :loading="saving" @click="save">保存设置</el-button></footer>
    </section>
    <section class="console-card setting-group-card">
      <header class="console-card-heading"><div><h2>模型与检索配置</h2><p>敏感密钥由部署环境注入；模型状态和供应商信息在单独页面查看。</p></div></header>
      <div class="settings-info-grid"><RouterLink to="/admin/models"><span>✳</span><div><strong>模型路由与熔断</strong><small>查看 Chat 主备、Embedding、Rerank 与 MCP 状态</small></div><b>→</b></RouterLink><div><span>▤</span><div><strong>知识检索</strong><small>pgvector 与 Elasticsearch 混合召回、RRF 融合</small></div><b>只读</b></div><div><span>⌘</span><div><strong>入库 Pipeline</strong><small>解析、切分、向量与关键词索引流程</small></div><RouterLink to="/admin/pipelines">查看 →</RouterLink></div></div>
      <p class="settings-note">此页未添加 Ragent 中不存在于 IntelHub 后端 API 的配置项，避免展示无法生效的“假设置”。</p>
    </section>
  </section>
</template>
