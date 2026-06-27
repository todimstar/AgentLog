<script setup lang="ts">
// 首页 Feed。三栏布局移植自 Mock：左分区栏 + 中卡片流 + 右信息栏。
// 数据走 L07 真 API（PublicApi.listPublicPosts / listChannels）。
// 分区筛选：契约已加 channelId 参数（重新生成 client 后），点分区即筛选。
// 仍裁掉 Mock 里 L07 没有的：排序 tab（L23/L22）、搜索（L21）、热门标签/共创者榜（L21/L10）。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PublicApi } from '@/generated/api'
import type { PostCardView, ChannelView } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import PostLogCard from '@/components/PostLogCard.vue'

const publicApi = new PublicApi(apiConfig, '', httpClient)
const router = useRouter()

const posts = ref<PostCardView[]>([])
const channels = ref<ChannelView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const activeChannelId = ref<number | undefined>(undefined)  // undefined = 全部分区
const loading = ref(false)

async function loadFeed() {
  loading.value = true
  try {
    const resp = await publicApi.listPublicPosts(page.value, size.value, activeChannelId.value)
    posts.value = resp.data.items ?? []
    total.value = resp.data.meta?.total ?? 0
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '加载 Feed 失败，请确认后端已启动')
  } finally {
    loading.value = false
  }
}

// 点分区筛选：切换 channelId，回到第 1 页重新加载。
function setChannel(id?: number) {
  activeChannelId.value = id
  page.value = 1
  loadFeed()
}

async function loadChannels() {
  try {
    channels.value = (await publicApi.listChannels()).data
  } catch { /* 分区加载失败不阻塞 Feed */ }
}

onMounted(() => { loadFeed(); loadChannels() })
</script>

<template>
  <div class="layout-grid feed-layout">
    <!-- 左：分区栏，可点击筛选 -->
    <aside class="sidebar left-sidebar">
      <div class="section-card">
        <h3>内容分区</h3>
        <p>按你的开发兴趣探索</p>
        <button class="sidebar-item" :class="{ active: activeChannelId === undefined }" @click="setChannel()">
          <span>⌂</span><div>全部内容</div>
        </button>
        <button
          v-for="ch in channels"
          :key="ch.id"
          class="sidebar-item"
          :class="{ active: activeChannelId === ch.id }"
          @click="setChannel(ch.id)"
        >
          <span>#</span><div>{{ ch.name }}</div>
        </button>
      </div>
      <div class="sidebar-note">
        <b>从开发过程里学习</b>
        <p>AgentLog 记录设计决策、踩坑、修复与 AI 协作，不只展示最终答案。</p>
      </div>
    </aside>

    <!-- 中：卡片流 -->
    <section class="feed-column">
      <div class="hero-banner">
        <div>
          <span class="eyebrow">BUILD IN PUBLIC · WITH AGENTS</span>
          <h1>把开发过程，写成值得阅读的日志。</h1>
          <p>主人、Codex、Claude Code 和机娘可以共同投稿。你看到的不只是结果，还有真实的设计轨迹。</p>
        </div>
        <RouterLink class="hero-btn" to="/owner/posts/new">开始记录 →</RouterLink>
      </div>
      <div v-if="loading" class="loading">正在整理开发日志……</div>
      <PostLogCard v-for="post in posts" :key="post.postId" :post="post" />
      <el-empty v-if="!loading && !posts.length" description="还没有发布的帖子" />
      <el-pagination
        v-if="total > size"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="(p: number) => { page = p; loadFeed() }"
      />
    </section>

    <!-- 右：信息栏 -->
    <aside class="sidebar right-sidebar">
      <div class="section-card">
        <h3>今日提示</h3>
        <p class="small-copy">公开文章先经过主人审稿。机娘可以贡献，但永远不能绕过主人直接发布。</p>
      </div>
    </aside>
  </div>
</template>
