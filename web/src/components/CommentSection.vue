<script setup lang="ts">
// L08 评论区组件。挂在 PostDetailPage 底部。
//
// 核心是【B站两层组树】：后端吐扁平 items（已按 root 排序），前端扫一遍按 rootCommentId
// 分组——每个一级评论是一"楼"，它下面所有二级回复（含回复二级的回复，都被扁平挂到同楼）平铺在楼内。
// 后端不组树，组树在前端：这正是契约"扁平 items + 超集结构字段"的设计意图。
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { PublicApi, WebApi } from '@/generated/api'
import type { CommentView, CreateCommentRequest } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import { useRouter } from 'vue-router'

const props = defineProps<{ postId: number }>()
const router = useRouter()
const publicApi = new PublicApi(apiConfig, '', httpClient)
const webApi = new WebApi(apiConfig, '', httpClient)

const allComments = ref<CommentView[]>([])
const loading = ref(false)
const replyTarget = ref<{ parentId: number; replyToId: number; label: string } | null>(null)
const replyInput = ref('')
const submitting = ref(false)

const time = (s?: string) => (s ? new Date(s).toLocaleString('zh-CN') : '')

// —— 把扁平 items 组成两层树 ——
// 后端已按 root_comment_id, created_at 排序，扫一遍按 root 分组即可。
// 一级评论 root=自己，自己成楼；二级回复 root=所属楼，归到对应楼下。
interface Floor {
  root: CommentView                       // 楼主（一级评论）
  replies: CommentView[]                  // 这层楼下的所有二级回复（平铺，含回复二级的）
}
const floors = computed<Floor[]>(() => {
  const map = new Map<number, Floor>()
  for (const c of allComments.value) {
    if (c.depth === 1) {
      // 一级：自己成楼。即便楼主已软删也保留楼结构（占位）。
      if (!map.has(c.id)) map.set(c.id, { root: c, replies: [] })
    } else {
      // 二级：归到所属楼。软删的楼可能 root 也是 DELETED，依然能找到楼。
      const floor = map.get(c.rootCommentId)
      if (floor) floor.replies.push(c)
      else map.set(c.rootCommentId, { root: allComments.value.find(x => x.id === c.rootCommentId)!, replies: [c] })
    }
  }
  return Array.from(map.values())
})

async function load() {
  loading.value = true
  try {
    const resp = await publicApi.listComments(props.postId)
    allComments.value = resp.data.items ?? []
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '加载评论失败')
  } finally {
    loading.value = false
  }
}

// 开始回复：点任意评论（一级或二级）的"回复"按钮。
// parentCommentId = 被回复的那条；replyToCommentId = @谁（展示）。
// 后端会把回复二级的请求【扁平化】挂到同楼——前端无需关心层级，只传目标 id。
function startReply(target: CommentView) {
  replyTarget.value = {
    parentId: target.id,
    replyToId: target.id,
    label: target.status === 'DELETED' ? '已删除的评论' : target.author.displayName,
  }
  replyInput.value = ''
}

function cancelReply() {
  replyTarget.value = null
  replyInput.value = ''
}

async function submit() {
  const text = replyInput.value.trim()
  if (!text) return
  submitting.value = true
  try {
    const body: CreateCommentRequest = replyTarget.value
      ? { content: text, parentCommentId: replyTarget.value.parentId, replyToCommentId: replyTarget.value.replyToId }
      : { content: text }
    await webApi.createComment(props.postId, body)
    await load()                  // 重新拉树，看到新评论就位
    cancelReply()
    ElMessage.success('评论成功')
  } catch (err: any) {
    // 401 = 未登录，引导去登录页（前端无全局登录态，靠后端 401 反馈）
    if (err?.response?.status === 401) {
      ElMessage.warning('请先登录再评论')
      router.push('/login')
    } else {
      ElMessage.error(err?.detail ?? '评论失败')
    }
  } finally {
    submitting.value = false
  }
}

async function softDelete(c: CommentView) {
  try {
    await webApi.deleteComment(c.id)
    await load()
    ElMessage.success('已删除')
  } catch (err: any) {
    if (err?.response?.status === 401) { ElMessage.warning('请先登录'); router.push('/login') }
    else ElMessage.error(err?.detail ?? '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="comment-section" v-loading="loading">
    <h3 class="section-title">💬 评论 <span class="count">{{ allComments.length }}</span></h3>

    <!-- 评论输入框：未指定回复目标时是一级评论；指定了则显示"回复 @谁"，可取消 -->
    <div class="comment-box">
      <div v-if="replyTarget" class="reply-hint">
        回复 @{{ replyTarget.label }}
        <button class="cancel-btn" @click="cancelReply">取消</button>
      </div>
      <textarea
        v-model="replyInput"
        :placeholder="replyTarget ? `回复 @${replyTarget.label}（挂到同楼，结构仍是二级）` : '写下你的评论…（未登录会提示去登录）'"
        rows="3"
      />
      <div class="box-actions">
        <button class="submit-btn" :disabled="!replyInput.trim() || submitting" @click="submit">
          {{ submitting ? '发送中…' : (replyTarget ? '发送回复' : '发表评论') }}
        </button>
      </div>
    </div>

    <!-- 两层树：每个 floor 是一个楼 -->
    <div v-if="floors.length" class="floors">
      <div v-for="f in floors" :key="f.root.id" class="floor">
        <!-- 楼主（一级） -->
        <div class="comment level-1">
          <div class="comment-head">
            <span class="author">{{ f.root.author.displayName }}</span>
            <span class="time">{{ time(f.root.createdAt) }}</span>
          </div>
          <p class="comment-body" :class="{ deleted: f.root.status === 'DELETED' }">{{ f.root.content }}</p>
          <div class="comment-actions">
            <button @click="startReply(f.root)">回复</button>
            <button v-if="f.root.status !== 'DELETED'" @click="softDelete(f.root)">删除</button>
          </div>
        </div>

        <!-- 楼内二级回复（平铺，含"回复二级"的回复——后端已扁平成同楼二级） -->
        <div v-for="r in f.replies" :key="r.id" class="comment level-2">
          <div class="comment-head">
            <span class="author">{{ r.author.displayName }}</span>
            <!-- replyToCommentId 指向同楼另一条时，显示"回复 @谁"；指向楼主/自己则省略 -->
            <span v-if="r.replyToCommentId && r.replyToCommentId !== f.root.id && r.replyToCommentId !== r.id" class="reply-to">
              回复 @{{ allComments.find(x => x.id === r.replyToCommentId)?.author.displayName ?? '某人' }}
            </span>
            <span class="time">{{ time(r.createdAt) }}</span>
          </div>
          <p class="comment-body" :class="{ deleted: r.status === 'DELETED' }">{{ r.content }}</p>
          <div class="comment-actions">
            <button @click="startReply(r)">回复</button>
            <button v-if="r.status !== 'DELETED'" @click="softDelete(r)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-else-if="!loading" description="还没有评论，来抢沙发" />
  </section>
</template>

<style scoped>
.comment-section { margin-top: 32px; }
.section-title { font-size: 18px; margin-bottom: 16px; }
.count { color: #9aa4b4; font-weight: normal; font-size: 14px; }

.comment-box { background: #f6f7f9; border-radius: 8px; padding: 12px; margin-bottom: 24px; }
.reply-hint { font-size: 13px; color: #1677ff; margin-bottom: 8px; }
.cancel-btn { background: none; border: none; color: #9aa4b4; cursor: pointer; margin-left: 8px; }
textarea { width: 100%; border: 1px solid #e0e3e8; border-radius: 6px; padding: 10px; font: inherit; resize: vertical; }
.box-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.submit-btn { background: #1677ff; color: #fff; border: none; border-radius: 6px; padding: 6px 18px; cursor: pointer; }
.submit-btn:disabled { opacity: .5; cursor: not-allowed; }

.floors { display: flex; flex-direction: column; gap: 20px; }
.floor { border-left: 3px solid #e0e3e8; padding-left: 16px; }
.comment { padding: 8px 0; }
.level-2 { margin-left: 8px; }
.comment-head { display: flex; gap: 10px; align-items: center; font-size: 13px; }
.author { font-weight: 600; }
.reply-to { color: #1677ff; }
.time { color: #9aa4b4; margin-left: auto; }
.comment-body { margin: 6px 0; white-space: pre-wrap; }
.comment-body.deleted { color: #bbb; font-style: italic; }
.comment-actions { display: flex; gap: 12px; }
.comment-actions button { background: none; border: none; color: #9aa4b4; cursor: pointer; font-size: 13px; }
.comment-actions button:hover { color: #1677ff; }
</style>
