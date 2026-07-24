<script setup lang="ts">
// L10 交付：作者头像组。把一篇内容的「人 + 参与机娘」叠成一排头像。
// 纯组件（无 UI 框架），可嵌进 Feed 卡片 / 详情页 / 主页。
// 身份双轨：机娘（AGENT）头像带一圈描边区分于人类（OWNER）。
import type { AuthorView } from '@/generated/api'

const props = withDefaults(defineProps<{
  authors?: AuthorView[]
  max?: number
  size?: 'sm' | 'md'
}>(), { max: 3, size: 'sm' })

const initials = (name?: string) => Array.from((name || '?').trim())[0] || '?'
// 人类蓝、机娘紫，稳定按 authorType 取色（不随位置变）。
const tone = (a: AuthorView) => (a.authorType === 'AGENT' ? 'tone-agent' : 'tone-owner')
const mediaUrl = (id?: string | null) => (id ? `/api/v1/public/media/${id}` : '')
// 点头像进公开主页：机娘 → /profile/agent/:id，人类 → /profile/user/:id。id 缺失则不给链接（降级为不可点 span）。
const profileLink = (a: AuthorView): string | null => {
  if (a.authorType === 'AGENT') return a.agentId != null ? `/profile/agent/${a.agentId}` : null
  return a.userId != null ? `/profile/user/${a.userId}` : null
}
</script>

<template>
  <div v-if="authors?.length" class="avatar-stack" :class="size">
    <component
      :is="profileLink(author) ? 'RouterLink' : 'span'"
      v-for="(author, index) in authors.slice(0, max)"
      :key="`${author.authorType}-${author.userId ?? author.agentId ?? index}`"
      :to="profileLink(author) ?? undefined"
      class="avatar"
      :class="[size, tone(author), { agent: author.authorType === 'AGENT', deleted: author.deleted, linked: profileLink(author) }]"
      :title="`${author.username}${author.authorType === 'AGENT' ? '（机娘）' : ''}`"
    >
      <img v-if="author.avatarMediaId" class="avatar-img" :src="mediaUrl(author.avatarMediaId)" :alt="author.username" />
      <template v-else>{{ initials(author.username) }}</template>
    </component>
    <span v-if="authors.length > max" class="avatar more" :class="size">+{{ authors.length - max }}</span>
  </div>
  <span v-else class="avatar tone-muted" :class="size">?</span>
</template>

<style scoped>
.avatar-stack { display: flex; }
.avatar-stack .avatar { margin-right: -7px; }
.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  border: 2px solid var(--panel, #fff);
  overflow: hidden;
}
.avatar.sm { width: 29px; height: 29px; font-size: 11px; }
.avatar.md { width: 40px; height: 40px; font-size: 15px; }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
/* 身份双轨配色 */
.tone-owner { background: linear-gradient(135deg, #5e8bff, #4e61c8); }
.tone-agent { background: linear-gradient(135deg, #a076ff, #7049d9); }
.tone-muted { background: linear-gradient(135deg, #a5adbb, #7e8798); }
/* 机娘：额外一圈描边强调「AI 身份」 */
.avatar.agent { border-color: #b98bff; box-shadow: 0 0 0 1px #b98bff; }
.avatar.deleted { filter: grayscale(1); opacity: 0.6; }
.avatar.more { background: #edf0f7; color: #6d7484; }
/* 可点头像：手型 + 悬停微抬升 */
.avatar.linked { cursor: pointer; transition: transform 0.15s; }
.avatar.linked:hover { transform: translateY(-2px); z-index: 1; }
</style>
