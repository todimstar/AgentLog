<script setup lang="ts">
// <script setup lang="ts"> 是 Vue3 组合式 API 的写法:
//   这里写的就是组件的逻辑,顶层变量/函数自动暴露给下面的 <template>。
//   lang="ts" = 用 TypeScript。
import { ref, onMounted } from 'vue'
import { PublicApi } from '@/generated/api'
import { apiConfig } from '@/api/http'

// ref(...) ≈ 一个"可响应的盒子":值变了,模板自动重新渲染。
//   类比 Java:像一个被观察的字段,赋值会触发 UI 更新。.value 才是真正的值。
const status = ref<string>('未连接后端(L04 只验证前端壳)')
const channelCount = ref<number | null>(null)

// 用生成的 PublicApi —— 注意:我们没手写任何 URL,直接调方法。
const publicApi = new PublicApi(apiConfig)

// onMounted = 组件挂载到页面后执行(类比:页面 ready 回调)。
onMounted(async () => {
  try {
    // 调用生成的 listChannels()。后端没起时会失败,这是预期的;
    // 它证明的是"前端调用链通、类型对",真实数据要等 L07 后端实现。
    const resp = await publicApi.listChannels()
    channelCount.value = resp.data.length
    status.value = '已连上后端 ✓'
  } catch {
    status.value = '后端未启动(正常,L04 不依赖后端)'
  }
})
</script>

<template>
  <main class="home">
    <h1>AgentLog</h1>
    <p class="subtitle">AI 辅助编程时代的开发日志社区</p>
    <p class="status">前端壳状态:{{ status }}</p>
    <p v-if="channelCount !== null">后端返回频道数:{{ channelCount }}</p>
    <p class="hint">L04:Vue 壳 + OpenAPI 生成链已就绪。</p>
  </main>
</template>

<style scoped>
/* scoped = 这些样式只作用于本组件,不污染全局 */
.home { max-width: 640px; margin: 80px auto; font-family: system-ui, sans-serif; text-align: center; }
.subtitle { color: #666; }
.status { margin-top: 24px; font-weight: 600; }
.hint { margin-top: 40px; color: #999; font-size: 14px; }
</style>
