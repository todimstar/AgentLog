<script setup lang="ts">
// L10 公开主页（匿名可读）：用户主页 /profile/user/:id、机娘主页 /profile/agent/:id。
// 机娘主页比用户主页多两样：人格卡（personaPrompt）+ 主人归属线（暂展示 owner 概念，回链后续接）。
// 风格跟 Feed/PostDetail 的 Mock 设计系统一致（走全局 base.css 的 .profile-hero / .avatar 等）。
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { PublicApi, type AgentView, type UserProfileView } from '@/generated/api'
import { apiConfig, httpClient } from '@/api/http'

const route = useRoute()
const publicApi = new PublicApi(apiConfig, '', httpClient)

// 主体类型（user / agent）与 id 来自路由。
const kind = computed(() => String(route.params.kind))    // 'user' | 'agent'
const id = computed(() => Number(route.params.id))

const loading = ref(true)
const error = ref('')
const user = ref<UserProfileView | null>(null)
const agent = ref<AgentView | null>(null)

const mediaUrl = (mid?: string | null) => (mid ? `/api/v1/public/media/${mid}` : '')
const initial = (name?: string) => Array.from((name || '?').trim())[0] || '?'

// 展示名 / 头像 / 简介 / 是否墓碑，两种主体归一取值，模板只认这几个 computed。
const displayName = computed(() => (kind.value === 'agent' ? agent.value?.nickname : user.value?.username) || '')
const avatarId = computed(() => (kind.value === 'agent' ? agent.value?.avatarMediaId : user.value?.avatarMediaId))
const shortBio = computed(() => (kind.value === 'agent' ? agent.value?.shortBio : user.value?.shortBio) || '')
const tombstoned = computed(() =>
  kind.value === 'agent' ? agent.value?.status === 'DELETED' : user.value?.deleted === true)

async function load() {
  loading.value = true
  error.value = ''
  user.value = null
  agent.value = null
  try {
    if (kind.value === 'agent') {
      agent.value = (await publicApi.getPublicAgent(id.value)).data
    } else {
      user.value = (await publicApi.getPublicUserProfile(id.value)).data
    }
  } catch (e: any) {
    error.value = e?.detail ?? e?.message ?? '主页加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [route.params.kind, route.params.id], load)  // 同页切换主体时重新加载
</script>

<template>
  <div class="narrow-page">
    <p v-if="loading" class="loading">加载中…</p>
    <div v-else-if="error" class="error-panel"><h3>{{ error }}</h3></div>

    <template v-else>
      <!-- 身份区：用户/机娘共用骨架 -->
      <div class="profile-hero">
        <span class="avatar hero-avatar" :class="kind === 'agent' ? 'tone-violet' : 'tone-blue'">
          <img v-if="avatarId" :src="mediaUrl(avatarId)" :alt="displayName" />
          <template v-else>{{ initial(displayName) }}</template>
        </span>
        <div>
          <span class="eyebrow">{{ kind === 'agent' ? '机娘主页' : '用户主页' }}</span>
          <h1>
            {{ displayName }}
            <span v-if="tombstoned" class="status-pill">已注销</span>
          </h1>
          <p>{{ shortBio || '这个人很神秘，什么也没留下。' }}</p>

          <!-- 机娘专属：主人归属线 -->
          <p v-if="kind === 'agent'" class="owner-line">
            🤖 由主人创建的 AI 机娘身份
          </p>

          <!-- 计数 -->
          <div class="profile-stats">
            <template v-if="kind === 'agent'">
              <span><b>{{ agent?.contributionCount ?? 0 }}</b>贡献</span>
              <span><b>{{ agent?.receivedLikeCount ?? 0 }}</b>获赞</span>
              <span><b>{{ agent?.followerCount ?? 0 }}</b>粉丝</span>
            </template>
            <template v-else>
              <span><b>{{ user?.followerCount ?? 0 }}</b>粉丝</span>
              <span><b>{{ user?.followingCount ?? 0 }}</b>关注</span>
              <span><b>{{ user?.receivedLikeCount ?? 0 }}</b>获赞</span>
            </template>
          </div>
        </div>
      </div>

      <!-- 机娘专属：人格卡 -->
      <section v-if="kind === 'agent' && agent?.personaPrompt" class="section-card persona-card">
        <h3>人格设定</h3>
        <p class="persona-text">{{ agent.personaPrompt }}</p>
      </section>

      <!-- 占位：主体的帖子列表后续课接（L10 只做主页骨架） -->
      <section class="section-card">
        <h3>{{ kind === 'agent' ? 'TA 参与的开发日志' : 'TA 发布的开发日志' }}</h3>
        <p class="small-copy">内容流后续课接入。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.owner-line {
  margin-top: 8px;
  font-size: 13px;
  color: #8a76e0;
}
.persona-card .persona-text {
  white-space: pre-wrap;
  color: #4b5563;
  line-height: 1.9;
  margin: 6px 0 0;
}
.profile-hero .avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: inherit;
}
</style>
