<script setup lang="ts">
// 一张开发日志卡片。结构/样式移植自 Mock 的 PostCard，字段裁剪到 L07 后端 PostCardView 实际有的：
//   postId/title/summary/channel/authors/contentOrigin/metrics/publishedAt。
// Mock 里有但 L07 后端还没有的（tags/coverVariant/toolSources/pinned/essence/channel.icon）先不渲染，
// 等 L21 标签 / L11 封面 / L23 精华 等课接上再补。
import type { AuthorView, PostCardView } from '@/generated/api'
import AuthorAvatarStack from '@/components/AuthorAvatarStack.vue'

defineProps<{ post: PostCardView }>()
const fmt = (n?: number) => (n && n > 999 ? `${(n / 1000).toFixed(1)}k` : `${n ?? 0}`)
const time = (s?: string) => (s ? new Date(s).toLocaleDateString('zh-CN') : '')
const authorNames = (authors?: AuthorView[]) => authors?.length ? authors.map(a => a.username).join(' · ') : '未知作者'
</script>

<template>
  <article class="post-card">
    <div class="post-main">
      <div class="post-meta-line">
        <span class="channel-chip">{{ post.channel?.name ?? '未分区' }}</span>
        <span v-if="post.contentOrigin !== 'HUMAN_ONLY'" class="origin-badge ai_assisted">AI 参与</span>
        <span class="time">{{ time(post.publishedAt) }}</span>
      </div>
      <RouterLink :to="`/posts/${post.postId}`" class="post-title">{{ post.title }}</RouterLink>
      <p class="post-summary">{{ post.summary }}</p>
      <div class="post-bottom">
        <div class="author-line">
          <AuthorAvatarStack :authors="post.authors" :max="3" size="sm" />
          <div>
            <b>{{ authorNames(post.authors) }}</b>
            <span>开发日志</span>
          </div>
        </div>
        <div class="metric-row">
          <span>◉ {{ fmt(post.metrics?.viewCount) }}</span>
          <span>♥ {{ fmt(post.metrics?.likeCount) }}</span>
          <span>💬 {{ fmt(post.metrics?.commentCount) }}</span>
          <span>★ {{ fmt(post.metrics?.collectionCount) }}</span>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}
</style>
