<script setup lang="ts">
// 单篇帖子详情页。调 GET /public/posts/{id}（后端 forum.getPublicPost）。
// 契约已对齐：生成的 PublicPostView 与后端返回一致，直接用生成模型，无需 as 绕过。
// L08：底部挂评论区组件，承接后端扁平 items → 前端组两层树。
// L09：文章底部 ♥/★ 变成可点击的点赞/收藏 toggle 按钮，调 /web/reactions/toggle + /web/collections/toggle。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PublicApi, WebApi } from '@/generated/api'
import type { PublicPostView } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import CommentSection from '@/components/CommentSection.vue'

const publicApi = new PublicApi(apiConfig, '', httpClient)
const webApi = new WebApi(apiConfig, '', httpClient)
const route = useRoute()
const router = useRouter()

const post = ref<PublicPostView | null>(null)
const loading = ref(false)
// 点赞/收藏的本地状态：刚 toggle 后用接口回吐的 active/count 直接刷新按钮，不重拉全文。
const liked = ref(false)
const likeCount = ref(0)
const collected = ref(false)
const collectionCount = ref(0)

async function load() {
  loading.value = true
  try {
    const id = Number(route.params.id)
    post.value = (await publicApi.getPublicPost(id)).data
    // 用后端返的计数初始化按钮数字。是否已赞/已收藏需登录后单独查（L09 后端有 getReactionState 但
    // 未在契约暴露，V1 简化：按钮初始未选中态，点了就 toggle，登录态会话下行为正确即可）。
    likeCount.value = post.value?.metrics?.likeCount ?? 0
    collectionCount.value = post.value?.metrics?.collectionCount ?? 0
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '帖子不存在或未发布')
    post.value = null
  } finally {
    loading.value = false
  }
}

async function toggleLike() {
  const id = Number(route.params.id)
  try {
    const resp = await webApi.toggleReaction({ targetType: 'POST', targetId: id })
    liked.value = resp.data.active
    likeCount.value = resp.data.count
  } catch (err: any) {
    if (err?.response?.status === 401) { ElMessage.warning('请先登录'); router.push('/login') }
    else ElMessage.error(err?.detail ?? '操作失败')
  }
}

async function toggleCollect() {
  const id = Number(route.params.id)
  try {
    const resp = await webApi.toggleCollection({ postId: id })
    collected.value = resp.data.active
    collectionCount.value = resp.data.count
  } catch (err: any) {
    if (err?.response?.status === 401) { ElMessage.warning('请先登录'); router.push('/login') }
    else ElMessage.error(err?.detail ?? '操作失败')
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
        <!-- ♥ 点赞 toggle 按钮：点了高亮，再点取消 -->
        <button class="action-btn" :class="{ active: liked }" @click="toggleLike" :title="liked ? '取消点赞' : '点赞'">
          {{ liked ? '♥' : '♡' }} {{ likeCount }}
        </button>
        <span>💬 {{ post.metrics?.commentCount ?? 0 }}</span>
        <!-- ★ 收藏 toggle -->
        <button class="action-btn" :class="{ active: collected }" @click="toggleCollect" :title="collected ? '取消收藏' : '收藏'">
          {{ collected ? '★' : '☆' }} {{ collectionCount }}
        </button>
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
.article-actions { display: flex; gap: 14px; align-items: center; margin-top: 20px; }
.action-btn { background: none; border: 1px solid #e0e3e8; border-radius: 16px; padding: 4px 12px; cursor: pointer; color: #5a6473; font-size: 14px; }
.action-btn:hover { border-color: #1677ff; color: #1677ff; }
.action-btn.active { color: #ff4d4f; border-color: #ff4d4f; }
</style>
