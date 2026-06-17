<script setup lang="ts">
// 发帖页：主人新建一篇开发日志。
// 产品流（方案A）：填表 → 保存草稿(createDraft) → 出现草稿态 → 一键发布(publishDraft)。
// 这样能展示"草稿 → 发布"两步，对应后端 TX-01 建草稿 + TX-02 发布两个事务。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { OwnerApi, PublicApi } from '@/generated/api'
import type { ChannelView } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'

// 用生成的 Api 类，传入带拦截器的 httpClient（自动带 Cookie + CSRF 头 + 错误翻译）。
const ownerApi = new OwnerApi(apiConfig, '', httpClient)
const publicApi = new PublicApi(apiConfig, '', httpClient)
const router = useRouter()

// —— 表单字段 ——
const title = ref('')
const channelId = ref<number | null>(null)
const summary = ref('')
const content = ref('')
const declaredExternalAiContent = ref(false)

// —— 分区下拉数据 ——
const channels = ref<ChannelView[]>([])

// —— 草稿态：保存草稿后存下 id + version，供发布时用（version 是乐观锁，发布要带回）——
const draftId = ref<number | null>(null)
const draftVersion = ref<number>(0)

const savingDraft = ref(false)
const publishing = ref(false)

// 进页面拉分区填充下拉。
onMounted(async () => {
  try {
    const resp = await publicApi.listChannels()
    channels.value = resp.data
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '加载分区失败，请确认后端已启动')
  }
})

// 第一步：保存草稿。
async function onSaveDraft() {
  if (!title.value || channelId.value === null || !content.value) {
    ElMessage.warning('标题、分区、正文均为必填')
    return
  }
  savingDraft.value = true
  try {
    const resp = await ownerApi.createDraft({
      title: title.value,
      channelId: channelId.value,
      content: content.value,
      summary: summary.value || undefined,
      declaredExternalAiContent: declaredExternalAiContent.value,
    })
    // 存下草稿 id 和 version：version 发布时要原样带回做乐观锁校验。
    draftId.value = resp.data.draftId
    draftVersion.value = resp.data.version
    ElMessage.success(`草稿已保存（#${resp.data.draftId}），可以发布了`)
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '保存草稿失败')
  } finally {
    savingDraft.value = false
  }
}

// 第二步：发布草稿。
async function onPublish() {
  if (draftId.value === null) return
  publishing.value = true
  try {
    const resp = await ownerApi.publishDraft(draftId.value, {
      // 带回保存草稿时的 version：后端比对，防止草稿被改后基于旧内容误发布。
      expectedDraftVersion: draftVersion.value,
      publishMode: 'AUTO_IF_ALLOWED',
    })
    ElMessage.success(`已发布！帖子 #${resp.data.postId}（v${resp.data.versionNo}）`)
    // 跳到公开详情页（若已实现），否则回首页。本课先回首页。
    router.push('/')
  } catch (err: any) {
    // 典型错误：DRAFT_VERSION_CONFLICT（草稿已被改）、DRAFT_ALREADY_PUBLISHED。
    ElMessage.error(err?.detail ?? '发布失败')
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <div class="post-new-wrap">
    <el-card class="post-new-card">
      <h2>写开发日志</h2>
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="title" placeholder="一句话标题" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="分区">
          <el-select v-model="channelId" placeholder="选择分区" style="width: 100%">
            <el-option
              v-for="ch in channels"
              :key="ch.id"
              :label="ch.name"
              :value="ch.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="摘要（可选）">
          <el-input v-model="summary" placeholder="一句话摘要" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="content"
            type="textarea"
            :rows="10"
            placeholder="写下你的开发日志 / 踩坑复盘…"
          />
        </el-form-item>
        <el-form-item>
          <el-switch v-model="declaredExternalAiContent" />
          <span class="switch-hint">声明本文含外部 AI 生成内容</span>
        </el-form-item>

        <div class="actions">
          <!-- 第一步：保存草稿 -->
          <el-button
            type="primary"
            :loading="savingDraft"
            :disabled="draftId !== null"
            @click="onSaveDraft"
          >
            {{ draftId === null ? '保存草稿' : `草稿 #${draftId} 已保存` }}
          </el-button>
          <!-- 第二步：草稿存好后才能发布 -->
          <el-button
            type="success"
            :loading="publishing"
            :disabled="draftId === null"
            @click="onPublish"
          >
            发布
          </el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.post-new-wrap {
  display: flex;
  justify-content: center;
  padding-top: 40px;
}
.post-new-card {
  width: 640px;
}
.switch-hint {
  margin-left: 8px;
  color: #666;
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
</style>
