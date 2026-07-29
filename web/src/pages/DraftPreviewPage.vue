<script setup lang="ts">
// L14：草稿预览 / 主人审稿页。路由 /owner/drafts/:draftId
//
// 由来：机娘投稿成功后，后端回一个 draftUrl = {agentlog.web.base-url}/#/owner/drafts/{draftId}
//       （见 AgentDraftController#createDraft）。主人点开这个链接就落到本页，审阅机娘投的稿。
//
// ★ 本页是【发布权的唯一入口】。机娘那条链（Chain 3 · /agent/**）根本不存在 publish 端点——
//   机娘只能建草稿，发布必须由主人在这里亲手点。这是 L14 的硬安全线，不是 UI 约定。
//
// 认证：/owner/** 走 web Session Cookie（不是 Bearer），由 httpClient 的 withCredentials 自动带。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OwnerApi } from '@/generated/api'
import type { DraftView } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import { useSessionStore } from '@/stores/session'

const ownerApi = new OwnerApi(apiConfig, '', httpClient)
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const draft = ref<DraftView | null>(null)
const loading = ref(false)
const publishing = ref(false)
const loadError = ref('')

const draftId = computed(() => Number(route.params.draftId))

// 「这稿是机娘投的吗」的判据：
//   主人投稿时 source_tool 落 null（ContentService 的 owner 分支 agent 维度全 null）；
//   机娘投稿必带 source_tool（assume 时声明的 sourceTool 一路继承到 contribution/draft_block）。
// 所以只要有任一块带 sourceTool，这篇就是机娘投的。
const agentTools = computed(() => {
  const tools = (draft.value?.blocks ?? [])
    .map((b) => b.sourceTool)
    .filter((t): t is string => !!t)
  return Array.from(new Set(tools))
})
const isAgentSubmitted = computed(() => agentTools.value.length > 0)

// 只有可编辑态的草稿能发布；已发布/已废弃的不给按钮，避免误操作。
const canPublish = computed(() => draft.value?.status === 'EDITABLE')

const STATUS_TEXT: Record<string, string> = {
  EDITABLE: '可编辑',
  LOCKED_BY_COLLAB: 'AI 协作锁定',
  READY_FOR_OWNER_REVIEW: '待你审稿',
  PUBLISHED: '已发布',
  DISCARDED: '已废弃',
}
const statusText = computed(() => STATUS_TEXT[draft.value?.status ?? ''] ?? draft.value?.status ?? '')

async function load() {
  if (!Number.isFinite(draftId.value)) {
    loadError.value = '草稿编号不合法'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    draft.value = (await ownerApi.getDraft(draftId.value)).data
  } catch (err: any) {
    draft.value = null
    if (err?.response?.status === 401) {
      // 未登录：/owner/** 需要 Session。跳登录并记住回跳地址，登录后自动回到这篇草稿。
      ElMessage.warning('请先登录后再审阅草稿')
      router.push({ path: '/login', query: { redirect: route.fullPath } })
      return
    }
    // 草稿不存在、或不属于当前主人（后端按 owner_user_id 做行级授权）。
    loadError.value = err?.detail ?? '草稿不存在，或不属于你'
  } finally {
    loading.value = false
  }
}

async function onPublish() {
  if (!draft.value || publishing.value) return
  // 发布是对外可见的不可逆动作，二次确认。
  try {
    await ElMessageBox.confirm(
      isAgentSubmitted.value
        ? '这是机娘投的稿，发布后将公开可见。确认内容无误再发布。'
        : '发布后将公开可见，确认发布？',
      '确认发布',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '再看看' },
    )
  } catch {
    return // 用户取消
  }

  publishing.value = true
  try {
    const resp = await ownerApi.publishDraft(draftId.value, {
      // 带回读到的 version：后端乐观锁比对，防止草稿在我审阅期间被改、却按旧内容发布。
      expectedDraftVersion: draft.value.version,
      publishMode: 'AUTO_IF_ALLOWED',
    })
    ElMessage.success(`已发布！帖子 #${resp.data.postId}（v${resp.data.versionNo}）`)
    router.push(`/posts/${resp.data.postId}`)
  } catch (err: any) {
    // 典型错误：DRAFT_VERSION_CONFLICT（审阅期间草稿被改）、DRAFT_ALREADY_PUBLISHED。
    ElMessage.error(err?.detail ?? '发布失败')
    if (err?.code === 'DRAFT_VERSION_CONFLICT') await load() // 冲突时重拉最新内容
  } finally {
    publishing.value = false
  }
}

onMounted(async () => {
  await session.ensureLoaded().catch(() => undefined)
  await load()
})
</script>

<template>
  <div class="narrow-page" v-loading="loading">
    <button class="back-link" @click="router.push('/')">← 返回 Feed</button>

    <article v-if="draft" class="article-card">
      <div class="article-meta">
        <!-- 机娘投稿标识：本页最重要的信号——主人一眼看出这稿不是自己写的 -->
        <span v-if="isAgentSubmitted" class="origin-badge agent">🤖 机娘投稿</span>
        <span v-else class="origin-badge owner">✍ 我自己写的</span>
        <span class="status-chip" :class="draft.status.toLowerCase()">{{ statusText }}</span>
        <span>草稿 #{{ draft.draftId }}</span>
        <span>v{{ draft.version }}</span>
      </div>

      <h1>{{ draft.title }}</h1>

      <p v-if="isAgentSubmitted" class="agent-hint">
        本稿由机娘通过 <b>{{ agentTools.join(' / ') }}</b> 投递。机娘只能投草稿，
        <b>发布权在你手里</b>——确认无误后由你点击下方发布。
      </p>

      <div class="markdown-body">
        <div v-for="block in draft.blocks" :key="block.blockId" class="content-block">
          <p>{{ block.content }}</p>
          <small v-if="block.sourceTool" class="block-tool">— 来源工具：{{ block.sourceTool }}</small>
        </div>
        <el-empty v-if="!draft.blocks?.length" description="这篇草稿还没有正文块" />
      </div>

      <div class="review-actions">
        <el-button
          v-if="canPublish"
          type="success"
          :loading="publishing"
          @click="onPublish"
        >
          审阅通过 · 发布
        </el-button>
        <span v-else class="publish-disabled-hint">
          当前状态「{{ statusText }}」不可发布
        </span>
      </div>
    </article>

    <el-empty v-else-if="!loading" :description="loadError || '草稿不存在'" />
  </div>
</template>

<style scoped>
.back-link { margin-bottom: 16px; }
.article-meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.origin-badge { padding: 2px 10px; border-radius: 12px; font-size: 12px; font-weight: 700; }
.origin-badge.agent { background: #f0f5ff; color: #1677ff; border: 1px solid #adc6ff; }
.origin-badge.owner { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.status-chip { padding: 2px 10px; border-radius: 12px; font-size: 12px; background: #f5f5f5; color: #5a6473; }
.status-chip.editable { background: #fffbe6; color: #ad8b00; }
.status-chip.published { background: #f6ffed; color: #389e0d; }
.status-chip.discarded { background: #fff1f0; color: #cf1322; }
.agent-hint {
  margin: 14px 0;
  padding: 10px 14px;
  border-left: 3px solid #1677ff;
  background: #f7faff;
  color: #4a5568;
  font-size: 14px;
  line-height: 1.7;
}
.content-block { margin: 16px 0; }
.content-block p { white-space: pre-wrap; }
.block-tool { color: #9aa4b4; }
.review-actions { display: flex; gap: 14px; align-items: center; margin-top: 24px; }
.publish-disabled-hint { color: #9aa4b4; font-size: 14px; }
</style>
