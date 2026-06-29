<script setup lang="ts">
// 单篇帖子详情页。调 GET /public/posts/{id}（后端 forum.getPublicPost）。
// 契约已对齐：生成的 PublicPostView 与后端返回一致，直接用生成模型，无需 as 绕过。
// L08：底部挂评论区组件，承接后端扁平 items → 前端组两层树。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PublicApi } from '@/generated/api'
import type { PublicPostView } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import CommentSection from '@/components/CommentSection.vue'

const publicApi = new PublicApi(apiConfig, '', httpClient)
const route = useRoute()
const router = useRouter()

const post = ref<PublicPostView | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const id = Number(route.params.id)
    post.value = (await publicApi.getPublicPost(id)).data
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '帖子不存在或未发布')
    post.value = null
  } finally {
    loading.value = false
  }
}

const time = (s?: string) => (s ? new Date(s).toLocaleString('zh-CN') : '')

onMounted(load)
</script>

<template>
  <div class="narrow-page" v-loading="loading">
    <button class="back-link" @click="router.push('/')">← 返回 Feed</button>

    <article v-if="post" class="article-card">
      <div class="article-meta">
        <span class="channel-chip">{{ post.channelName }}</span>
        <span v-if="post.contentOrigin !== 'HUMAN_ONLY'" class="origin-badge ai_assisted">AI 参与</span>
        <span>v{{ post.versionNo }}</span>
        <span>{{ time(post.publishedAt) }}</span>
      </div>
      <h1>{{ post.title }}</h1>
      <p class="article-lead">{{ post.summary }}</p>

      <div class="markdown-body">
        <div v-for="block in post.blocks" :key="block.blockId" class="content-block">
          <p>{{ block.content }}</p>
          <small v-if="block.sourceTool" class="block-tool">— {{ block.sourceTool }}</small>
        </div>
      </div>

      <div class="article-actions">
        <span>👁 {{ post.metrics?.viewCount ?? 0 }}</span>
        <span>♥ {{ post.metrics?.likeCount ?? 0 }}</span>
        <span>💬 {{ post.metrics?.commentCount ?? 0 }}</span>
        <span>★ {{ post.metrics?.collectionCount ?? 0 }}</span>
      </div>
    </article>

    <!-- L08 评论区：扁平 items → 前端组两层树 -->
    <CommentSection v-if="post" :post-id="Number(route.params.id)" />

    <el-empty v-else-if="!loading" description="帖子不存在或未发布" />
  </div>
</template>

<style scoped>
.back-link { margin-bottom: 16px; }
.article-meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.content-block { margin: 16px 0; }
.content-block p { white-space: pre-wrap; }
.block-tool { color: #9aa4b4; }
</style>
