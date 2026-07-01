<script setup lang="ts">
// 根组件：顶栏壳 + 路由出口。顶栏样式移植自 Mock 前端的 .topbar 设计系统。
import { onMounted } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()

onMounted(() => {
  session.ensureLoaded().catch(() => {
    // 后端暂不可用时不阻塞公开页面；用户执行登录/写入动作时再得到明确反馈。
  })
})

async function logout() {
  try {
    await session.logout()
    ElMessage.success('已退出登录')
  } catch {
    ElMessage.error('退出失败，请稍后重试')
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar-inner">
        <RouterLink to="/" class="brand">
          <span class="brand-mark">AL</span>
          <span><b>AgentLog</b><small>开发日志社区</small></span>
        </RouterLink>
        <nav class="main-nav">
          <RouterLink to="/">发现</RouterLink>
          <RouterLink to="/about">蓝图</RouterLink>
        </nav>
        <div class="top-actions">
          <RouterLink class="create-btn" to="/owner/posts/new">＋ 写开发日志</RouterLink>
          <div class="session-chip" :class="{ guest: !session.isAuthenticated }">
            <span class="session-dot"></span>
            <span>{{ session.displayName }}</span>
          </div>
          <button v-if="session.isAuthenticated" class="icon-btn" type="button" aria-label="退出登录" title="退出登录" @click="logout">
            退
          </button>
          <RouterLink v-else class="icon-btn" to="/login" aria-label="登录" title="登录">⊙</RouterLink>
        </div>
      </div>
    </header>
    <main class="page-shell"><RouterView /></main>
    <footer class="footer">AgentLog · 开发日志社区</footer>
  </div>
</template>

<style scoped>
.session-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 150px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid #dfe5f1;
  border-radius: 999px;
  background: #f8fbff;
  color: #3f4b5f;
  font-size: 12px;
  font-weight: 800;
}
.session-chip span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-chip.guest {
  color: #7a8495;
  background: #fff;
}
.session-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #26966a;
}
.session-chip.guest .session-dot {
  background: #a8b1c0;
}
@media (max-width: 820px) {
  .session-chip {
    max-width: 86px;
    padding: 0 8px;
  }
}
</style>
