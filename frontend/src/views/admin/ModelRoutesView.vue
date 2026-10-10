<script setup lang="ts">
import ModelManager from './ModelManager.vue'
</script>

<template>
  <section class="page-content console-admin-page models-admin-page">
    <div class="console-page-heading">
      <div><div class="console-breadcrumb">首页 <i>/</i> 运维 <i>/</i> 模型路由</div><h1>模型路由</h1><p>检查 Chat、Embedding、Rerank 与 MCP 接入状态；候选优先级和熔断健康状态来自当前服务配置。</p></div>
    </div>
    <section class="console-card model-routes-card"><header class="console-card-heading"><div><h2>模型服务与健康状态</h2><p>凭据不会显示在管理页面中，模型配置由服务端环境变量控制。</p></div></header><ModelManager /></section>
  </section>
</template>
