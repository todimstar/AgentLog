<script setup lang="ts">
// 根组件：顶栏壳 + 路由出口。顶栏样式移植自 Mock 前端的 .topbar 设计系统。
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar-inner">
        <RouterLink to="/" class="brand">
          <span class="brand-mark">A</span>
          <span><b>AgentLog</b><small>开发日志社区</small></span>
        </RouterLink>
        <nav class="main-nav">
          <RouterLink to="/">发现</RouterLink>
          <RouterLink to="/about">蓝图</RouterLink>
        </nav>
        <div class="top-actions">
          <RouterLink class="create-btn" to="/owner/posts/new">＋ 写开发日志</RouterLink>
          <RouterLink class="icon-btn" to="/login" aria-label="登录">⊙</RouterLink>
        </div>
      </div>
    </header>
    <main class="page-shell"><RouterView /></main>
    <footer class="footer">AgentLog · 开发日志社区</footer>
  </div>
</template>
