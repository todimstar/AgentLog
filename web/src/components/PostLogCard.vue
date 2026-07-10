<script setup lang="ts">
// 一张开发日志卡片。结构/样式移植自 Mock 的 PostCard，字段裁剪到 L07 后端 PostCardView 实际有的：
//   postId/title/summary/channel/authors/contentOrigin/metrics/publishedAt。
// Mock 里有但 L07 后端还没有的（tags/coverVariant/toolSources/pinned/essence/channel.icon）先不渲染，
// 等 L21 标签 / L11 封面 / L23 精华 等课接上再补。
import type { AuthorView, PostCardView } from '@/generated/api'

defineProps<{ post: PostCardView }>()
const fmt = (n?: number) => (n && n > 999 ? `${(n / 1000).toFixed(1)}k` : `${n ?? 0}`)
const time = (s?: string) => (s ? new Date(s).toLocaleDateString('zh-CN') : '')
const initials = (name?: string) => Array.from((name || '?').trim())[0] || '?'
const authorNames = (authors?: AuthorView[]) => authors?.length ? authors.map(a => a.username).join(' · ') : '未知作者'
const tone = (index: number) => ['tone-blue', 'tone-green', 'tone-amber'][index % 3]
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
          <div v-if="post.authors?.length" class="avatar-stack sm">
            <span
              v-for="(author, index) in post.authors.slice(0, 3)"
              :key="`${author.authorType}-${author.userId ?? author.agentId ?? index}`"
              :class="['avatar', 'sm', tone(index)]"
              :title="author.username"
            >
              <img v-if="author.avatarMediaId" class="avatar-img" :src="`/api/v1/public/media/${author.avatarMediaId}`" :alt="author.username" />
              <template v-else>{{ initials(author.username) }}</template>
            </span>
          </div>
          <span v-else class="avatar sm tone-muted">?</span>
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
