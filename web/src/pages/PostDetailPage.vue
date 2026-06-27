<script setup lang="ts">
// 单篇帖子详情页。调 GET /public/posts/{id}（后端 forum.getPublicPost）。
// ⚠️ 注意：生成的 PublicPostView 模型字段（contract）与后端实际返回有出入
//   （后端返回 summary/channelName/contentOrigin/versionNo/publishedAt，生成模型没有这些）。
//   本页以【后端实际返回】为准，用一个本地类型描述真实结构，避免被生成类型卡住。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PublicApi } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'

// 后端实际返回结构（与 forum/api/dto/response/PublicPostView.java 对齐）
interface RealBlock { blockId: number; displayOrder: number; content: string; sourceTool: string | null }
interface RealPostView {
  postId: number; title: string; summary: string; channelName: string
  contentOrigin: string; versionNo: number; publishedAt: string; blocks: RealBlock[]
}

const publicApi = new PublicApi(apiConfig, '', httpClient)
const route = useRoute()
const router = useRouter()

const post = ref<RealPostView | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const id = Number(route.params.id)
    const resp = await publicApi.getPublicPost(id)
    post.value = resp.data as unknown as RealPostView
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
    </article>

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
