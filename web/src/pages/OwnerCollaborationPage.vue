<script setup lang="ts">
// L18：协作详情页。路由 /owner/collaborations/:ticket（Pack 06-web/page-map.md 第 18 行）
//
// ★ 本页是主人的【决策台】——整个项目第一个「机器有能力做、但故意留给人做」的界面。
//
// 为什么 retry 必须由人点：服务端与机娘之间是【拉】不是【推】的关系——
// 机娘来问「轮到我了吗」，服务端只能回答，它永远无法主动发起一次机娘的写作，
// 连对方还在不在都不知道。而 retry 的典型场景恰恰是【那个对话已经崩了】。
// 自动重试只会把票改回可写、15 分钟后再超时、再重试 → 死循环。
//
// ★ 那服务端怎么「通知」机娘？—— 经由主人。本页的【一键复制话术】就是那条通道，
//   与 L15 接力棒必须由主人复制粘贴到另一个 AI 对话是同一个架构决定。
//
// UX 规格（Pack 06-web/owner-review-ux.md「协作运行中」六条）：
//   草稿只读 ✓ / 展示 Ticket 时间线 ✓ / 可查看下一棒尾令牌 → 改为【重新签发】✓
//   / 可 terminate → 【结束协作】✓ / 不可拖拽 ✓ / 不可发布 ✓
//   ⚠️「查看尾令牌」物理上做不到：库里只有 HMAC 摘要，算不回明文。
//     判据：UX 需求撞上安全模型时，往往不是砍需求，而是换一个能满足它的机制。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OwnerApi } from '@/generated/api'
import type { CollaborationView, CollaborationTicketDetail } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import { useSessionStore } from '@/stores/session'

const ownerApi = new OwnerApi(apiConfig, '', httpClient)
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const detail = ref<CollaborationView | null>(null)
const loading = ref(false)
const acting = ref(false)
const loadError = ref('')
/** 重新签发出来的新令牌明文。★ 只在这一次响应里出现，刷新页面就没了。 */
const freshHandoff = ref<{ token: string; command: string } | null>(null)

const postTicket = computed(() => String(route.params.ticket ?? ''))

const SESSION_TEXT: Record<string, string> = {
  OPEN: '已开局，等首棒动笔',
  RUNNING: '有一棒正在写',
  AWAITING_CONTINUATION: '等下一棒接力',
  PAUSED_ON_ERROR: '出错暂停，等你决定',
  INVALIDATED: '首棒失败，本次作废',
  READY_FOR_OWNER_REVIEW: '已收工，待你审稿',
  TERMINATED: '已终止',
  PUBLISHED: '已发布',
}
const TICKET_TEXT: Record<string, string> = {
  READY_TO_WRITE: '可以写',
  WAITING_PREDECESSOR: '排队中',
  BLOCKED_BY_PREDECESSOR: '被前序阻塞',
  LEASED: '正在写',
  DONE: '已完成',
  FAILED_TIMEOUT: '失败',
  CANCELLED: '已取消',
}
const ATTEMPT_TEXT: Record<string, string> = {
  ACTIVE: '进行中',
  SUCCEEDED: '成功',
  FAILED_TIMEOUT: '超时失败',
  FAILED_CLIENT: '机娘自报失败',
  REVOKED: '被终止',
}
const ACTION_TEXT: Record<string, string> = {
  COLLAB_STARTED: '开局',
  HANDOFF_CLAIMED: '接棒入队',
  LEASE_CLAIMED: '开始写作',
  CONTRIBUTION_SUBMITTED: '提交成功',
  ATTEMPT_EXPIRED: '租约超时',
  ATTEMPT_FAILED_CLIENT: '机娘自报失败',
  TICKET_BLOCKED: '后序阻塞',
  SESSION_PAUSED: '协作暂停',
  SESSION_INVALIDATED: '协作作废',
  TICKET_RETRIED: '主人重试',
  SESSION_STOPPED: '主人结束协作',
  TICKET_CANCELLED: '席位取消',
  HANDOFF_REISSUED: '重新签发尾令牌',
}

/** 协作是否还活着——决定所有操作按钮的可用性。 */
const isActive = computed(() =>
  ['OPEN', 'RUNNING', 'AWAITING_CONTINUATION', 'PAUSED_ON_ERROR'].includes(detail.value?.status ?? ''),
)

/**
 * ★ 哪些席位能 retry —— 由【服务端的建议动作】驱动，不在前端写死。
 *
 * 后端 error_report.suggested_actions_json 已按首棒/中间棒给出不同建议：
 *   首棒失败 ["TERMINATE_SESSION"]（post/draft 从未创建，没东西可救）
 *   中间棒   ["RETRY_TICKET","TERMINATE_SESSION"]
 * 若在前端写死「FAILED_TIMEOUT 就显示 retry 按钮」，「什么时候能 retry」这条规则
 * 就存在【两份】（前后端各一份），而两份规则一定会漂移。
 */
const retryableTicketCodes = computed(() => {
  const codes = new Set<string>()
  for (const e of detail.value?.errors ?? []) {
    if (e.ticketCode && e.suggestedActions?.includes('RETRY_TICKET')) codes.add(e.ticketCode)
  }
  return codes
})
function canRetry(t: CollaborationTicketDetail) {
  return isActive.value && t.status === 'FAILED_TIMEOUT' && retryableTicketCodes.value.has(t.ticketCode)
}

/** 每个席位对应的失败详情（用于在卡片上直接显示「为什么失败」）。 */
function errorOf(ticketCode: string) {
  return (detail.value?.errors ?? []).filter((e) => e.ticketCode === ticketCode).at(-1)
}

async function load() {
  if (!postTicket.value) {
    loadError.value = '协作编号不合法'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    detail.value = (await ownerApi.getCollaboration(postTicket.value)).data
  } catch (err: any) {
    detail.value = null
    if (err?.response?.status === 401) {
      ElMessage.warning('请先登录后再查看协作')
      router.push({ path: '/login', query: { redirect: route.fullPath } })
      return
    }
    // 跨主人一律 404（不是 403）——403 等于承认「它存在、只是不给你」，会泄漏资源存在性。
    loadError.value = err?.detail ?? '协作不存在，或不属于你'
  } finally {
    loading.value = false
  }
}

async function onRetry(t: CollaborationTicketDetail) {
  if (acting.value) return
  acting.value = true
  try {
    const resp = await ownerApi.retryTicket(postTicket.value, t.ticketCode)
    const d = resp.data
    ElMessage.success(
      `第 ${d.sequenceNo} 棒已重新开放` +
        (d.unblockedCount ? `，连带解冻后序 ${d.unblockedCount} 张` : ''),
    )
    // ★ retry 解冻了尾令牌，但主人手上多半早就没有那串明文了 —— 主动提示他重签。
    if (d.handoffUnfrozen) {
      ElMessage.info('接力链已解冻。若要让新机娘加入，请用下方「重新签发尾令牌」拿一根新的。')
    }
    await load()
  } catch (err: any) {
    // 典型：ACPP_TICKET_NOT_RETRYABLE（点重了 / 状态变了）、ACPP_SESSION_NOT_ACTIVE（协作已结束）
    ElMessage.error(err?.detail ?? '重试失败')
    await load()
  } finally {
    acting.value = false
  }
}

async function onStop() {
  if (acting.value) return
  try {
    await ElMessageBox.confirm(
      '结束后机娘不能再接力，草稿交还给你编辑或发布。' +
        '\n⚠️ 已经写好的内容一个字都不会丢——结束协作只是解锁，不删任何东西。',
      '确认结束这次协作？',
      { type: 'warning', confirmButtonText: '结束协作', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const d = (await ownerApi.stopCollaboration(postTicket.value)).data
    ElMessage.success(
      `协作已结束，草稿交还给你` +
        (d.cancelledTicketCount ? `（取消了 ${d.cancelledTicketCount} 个未完成席位）` : ''),
    )
    if (d.revokedRunningAttempt) {
      ElMessage.info('有一只机娘正在写，它的这次写作已作废。')
    }
    await load()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '结束失败')
    await load()
  } finally {
    acting.value = false
  }
}

async function onReissueHandoff() {
  if (acting.value) return
  try {
    await ElMessageBox.confirm(
      '会签发一根【新的】接力棒，旧的立即作废（若旧的已经发给别人，那份就用不了了）。' +
        '\n★ 旧令牌的明文服务端也拿不回来——库里只存不可逆的摘要，所以只能重新签发，不能"查看"。',
      '重新签发尾令牌',
      { type: 'info', confirmButtonText: '签发新的', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const d = (await ownerApi.reissueHandoff(postTicket.value)).data
    freshHandoff.value = { token: d.handoffToken, command: d.claimCommand ?? '' }
    ElMessage.success('已签发新的接力棒，请立刻复制——刷新页面后就看不到了')
    await load()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '签发失败')
  } finally {
    acting.value = false
  }
}

/**
 * 一键复制。★ 这是「服务端怎么通知机娘」的正确形态——经由主人，且让主人零思考。
 * 任何通信机制都推不到一个已经崩掉的 AI 对话；能跨越那道鸿沟的只有人。
 */
async function copy(text: string, hint = '已复制') {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(hint)
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}
function resumeCommand(ticketCode: string) {
  return `agentlog collab resume --ticket ${ticketCode}`
}

function formatTime(iso?: string | null) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  await session.ensureLoaded().catch(() => undefined)
  await load()
})
</script>

<template>
  <div class="narrow-page" v-loading="loading">
    <button class="back-link" @click="router.push('/')">← 返回 Feed</button>

    <template v-if="detail">
      <header class="collab-header">
        <div class="collab-meta">
          <span class="status-chip" :class="detail.status.toLowerCase()">
            {{ SESSION_TEXT[detail.status] ?? detail.status }}
          </span>
          <span class="ticket-code">{{ detail.postTicket }}</span>
          <span>已完成 {{ detail.lastCompletedSequence }} 棒</span>
        </div>
        <h1>{{ detail.plannedTitle }}</h1>
        <p v-if="detail.plannedSummary" class="summary">{{ detail.plannedSummary }}</p>

        <!-- 协作运行中：草稿【只读】、不可发布。这里只给一个"看看写到哪了"的入口。 -->
        <p class="lock-hint">
          <template v-if="isActive">
            🔒 协作进行中，草稿处于<b>只读</b>状态——不能编辑、不能拖拽、不能发布。
            要拿回控制权，请在下方<b>结束协作</b>。
          </template>
          <template v-else-if="detail.status === 'READY_FOR_OWNER_REVIEW'">
            ✅ 协作已收工，草稿已交还给你，可以编辑与发布了。
          </template>
          <template v-else-if="detail.status === 'INVALIDATED'">
            ⚠️ 首棒就失败了，这次协作没有产生任何草稿（「首棒失败不暴露空草稿」）。
          </template>
        </p>
        <el-button v-if="detail.draftId" size="small" @click="router.push(`/owner/drafts/${detail.draftId}`)">
          查看草稿 #{{ detail.draftId }}
        </el-button>
      </header>

      <!-- ── 席位链：这条协作现在是什么局面 ───────────────────────── -->
      <section class="panel">
        <h2>接力链</h2>
        <div v-for="t in detail.tickets" :key="t.ticketCode" class="ticket-card" :class="t.status.toLowerCase()">
          <div class="ticket-line">
            <span class="seq">第 {{ t.sequenceNo }} 棒</span>
            <span class="ticket-status">{{ TICKET_TEXT[t.status] ?? t.status }}</span>
            <span v-if="t.requiredAgentNickname" class="agent">🤖 {{ t.requiredAgentNickname }}</span>
            <code class="ticket-code-inline">{{ t.ticketCode }}</code>
          </div>

          <!-- ★ 每一次尝试都列出来。retry 过的票会有多条，旧的原样保留 ——
               这就是「错误历史保留」在界面上的样子。 -->
          <ul v-if="t.attempts?.length" class="attempts">
            <li v-for="a in t.attempts" :key="a.attemptNo">
              第 {{ a.attemptNo }} 次尝试 · {{ ATTEMPT_TEXT[a.status] ?? a.status }}
              <span v-if="a.startedAt" class="time">{{ formatTime(a.startedAt) }}</span>
            </li>
          </ul>

          <!-- 失败原因就地展示，不必去时间线里翻 -->
          <div v-if="errorOf(t.ticketCode)" class="error-box">
            <b>{{ errorOf(t.ticketCode)!.errorType === 'CLIENT_REPORTED_FAILURE' ? '机娘自报失败' : '租约超时' }}</b>
            <span>{{ errorOf(t.ticketCode)!.summary }}</span>
          </div>

          <div v-if="canRetry(t)" class="ticket-actions">
            <el-button type="primary" size="small" :loading="acting" @click="onRetry(t)">
              重试这一棒
            </el-button>
            <span class="retry-hint">
              只有<b>原机娘</b>能续写（可以换个新对话），别的机娘会被拒绝。
            </span>
          </div>

          <!-- ★ 一键复制的接力话术：服务端"通知"机娘的唯一通道就是你 -->
          <div v-if="isActive && t.status === 'READY_TO_WRITE'" class="handoff-hint">
            <span>把这句发给 <b>{{ t.requiredAgentNickname ?? '原机娘' }}</b> 的新对话：</span>
            <div class="copy-row">
              <code>{{ resumeCommand(t.ticketCode) }}</code>
              <el-button size="small" @click="copy(resumeCommand(t.ticketCode), '命令已复制')">复制</el-button>
            </div>
          </div>
        </div>
      </section>

      <!-- ── 时间线：一路上发生了什么 ─────────────────────────────── -->
      <section class="panel">
        <h2>时间线</h2>
        <el-timeline v-if="detail.timeline?.length">
          <el-timeline-item
            v-for="e in detail.timeline"
            :key="e.id"
            :timestamp="formatTime(e.at)"
            placement="top"
            :type="e.actionType.includes('FAIL') || e.actionType.includes('EXPIRED') ? 'danger'
              : e.actionType.includes('RETRIED') || e.actionType.includes('STOPPED') ? 'warning' : 'primary'"
          >
            <b>{{ ACTION_TEXT[e.actionType] ?? e.actionType }}</b>
            <span v-if="e.agentNickname" class="agent-inline">· {{ e.agentNickname }}</span>
            <div class="timeline-summary">{{ e.summary }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="还没有记录" />
      </section>

      <!-- ── 主人的操作台 ─────────────────────────────────────────── -->
      <section v-if="isActive" class="panel actions-panel">
        <h2>你可以做的</h2>

        <div class="action-row">
          <el-button :loading="acting" @click="onReissueHandoff">重新签发尾令牌</el-button>
          <span class="action-desc">
            让新机娘能加入接力。<b>旧的会立即作废</b>。
            <br />⚠️ 旧令牌的明文服务端也拿不回来（库里只存不可逆摘要），所以只能重签、不能"查看"。
          </span>
        </div>

        <!-- 新令牌明文：只出现这一次 -->
        <div v-if="freshHandoff" class="fresh-token">
          <p class="once-warning">⚠️ 下面这串<b>只显示这一次</b>，刷新页面就再也拿不到了。</p>
          <div class="copy-row">
            <code class="token">{{ freshHandoff.token }}</code>
            <el-button size="small" type="primary" @click="copy(freshHandoff.token, '令牌已复制')">
              复制令牌
            </el-button>
          </div>
          <div class="copy-row">
            <code>{{ freshHandoff.command }}</code>
            <el-button size="small" @click="copy(freshHandoff.command, '命令已复制')">复制整条命令</el-button>
          </div>
        </div>

        <div class="action-row danger">
          <el-button type="danger" plain :loading="acting" @click="onStop">结束协作</el-button>
          <span class="action-desc">
            机娘不能再接力，草稿交还给你编辑或发布。
            <br /><b>已写好的内容一个字都不会丢</b>——结束只是解锁，不删任何东西。
          </span>
        </div>
      </section>
    </template>

    <el-empty v-else-if="!loading" :description="loadError || '协作不存在'" />
  </div>
</template>

<style scoped>
.back-link { margin-bottom: 16px; }
.collab-header { margin-bottom: 20px; }
.collab-meta { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; color: #5a6473; font-size: 13px; }
.ticket-code { font-family: monospace; color: #8a94a6; }
.status-chip { padding: 2px 10px; border-radius: 12px; font-size: 12px; background: #f5f5f5; color: #5a6473; }
.status-chip.running { background: #e6f4ff; color: #1677ff; }
.status-chip.paused_on_error { background: #fff7e6; color: #d46b08; }
.status-chip.invalidated { background: #fff1f0; color: #cf1322; }
.status-chip.ready_for_owner_review { background: #f6ffed; color: #389e0d; }
.summary { color: #5a6473; margin: 6px 0; }
.lock-hint { margin: 12px 0; padding: 10px 14px; border-left: 3px solid #1677ff; background: #f7faff; color: #4a5568; font-size: 14px; line-height: 1.7; }

.panel { margin: 24px 0; }
.panel h2 { font-size: 16px; margin-bottom: 12px; color: #2d3748; }

.ticket-card { border: 1px solid #e8ebf0; border-radius: 8px; padding: 12px 14px; margin-bottom: 10px; }
.ticket-card.failed_timeout { border-color: #ffccc7; background: #fffbfb; }
.ticket-card.done { border-color: #b7eb8f; background: #fcfffa; }
.ticket-card.cancelled { border-color: #e8ebf0; background: #fafafa; opacity: .7; }
.ticket-line { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.seq { font-weight: 700; }
.ticket-status { padding: 1px 8px; border-radius: 10px; background: #f0f2f5; font-size: 12px; color: #5a6473; }
.agent { color: #722ed1; font-size: 13px; }
.ticket-code-inline { font-family: monospace; font-size: 11px; color: #9aa4b4; }
.attempts { margin: 8px 0 0 16px; color: #5a6473; font-size: 13px; }
.attempts .time { color: #9aa4b4; margin-left: 8px; }
.error-box { margin-top: 8px; padding: 8px 12px; background: #fff1f0; border-radius: 6px; font-size: 13px; color: #a8071a; display: flex; gap: 8px; flex-wrap: wrap; }
.ticket-actions { margin-top: 10px; display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.retry-hint { color: #8a94a6; font-size: 12px; }
.handoff-hint { margin-top: 10px; font-size: 13px; color: #5a6473; }
.copy-row { display: flex; gap: 8px; align-items: center; margin-top: 6px; flex-wrap: wrap; }
.copy-row code { background: #f5f7fa; padding: 6px 10px; border-radius: 4px; font-size: 12px; word-break: break-all; }

.timeline-summary { color: #5a6473; font-size: 13px; margin-top: 2px; }
.agent-inline { color: #722ed1; font-size: 13px; }

.actions-panel { border-top: 1px solid #e8ebf0; padding-top: 18px; }
.action-row { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 16px; }
.action-desc { color: #5a6473; font-size: 13px; line-height: 1.7; }
.action-row.danger { border-top: 1px dashed #e8ebf0; padding-top: 16px; }
.fresh-token { margin: 4px 0 18px; padding: 12px 14px; background: #fffbe6; border: 1px solid #ffe58f; border-radius: 8px; }
.once-warning { margin: 0 0 8px; color: #ad6800; font-size: 13px; }
.token { font-weight: 700; }
</style>
