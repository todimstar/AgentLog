<script setup lang="ts">
// L10-L11 修复：个人设置页——头像"上传→绑定→展示"完整链路的操作入口。
// L10 补做：机娘管理区块（建/列/墓碑删除自己的机娘），并进设置页（方案四·功能少不单开页）。
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OwnerApi, type CreateUploadSlotRequestContentTypeEnum, type AgentView } from '@/generated/api'
import { apiConfig, httpClient } from '@/api/http'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const ownerApi = new OwnerApi(apiConfig, '', httpClient)
const uploading = ref(false)

onMounted(() => {
  session.ensureLoaded()
  if (session.isAuthenticated) loadAgents()
})

// 头像展示 URL：后端 /public/media/{id} 会 302 到 MinIO 预签名 GET（浏览器无感跟随）。
const avatarUrl = computed(() =>
  session.user?.avatarMediaId ? `/api/v1/public/media/${session.user.avatarMediaId}` : '')
const initial = computed(() => Array.from((session.username || '?').trim())[0] || '?')

const ALLOWED = ['image/jpeg', 'image/png', 'image/webp']

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!ALLOWED.includes(file.type)) { ElMessage.warning('仅支持 JPEG / PNG / WebP'); input.value = ''; return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); input.value = ''; return }

  uploading.value = true
  try {
    // 1) 申请上传槽：后端建 PENDING 记录 + 签发预签名 PUT URL（不碰文件）。
    const slot = (await ownerApi.createUploadSlot({
      originalFilename: file.name, contentType: file.type as CreateUploadSlotRequestContentTypeEnum,
      declaredSizeBytes: file.size, aiGeneratedDeclared: false,
    })).data
    // 2) 直传 MinIO：原生 fetch 直传预签名 URL——不经后端、不带 Cookie/CSRF。
    const put = await fetch(slot.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file })
    if (!put.ok) throw new Error('直传对象存储失败')
    // 3) finalize：后端 HEAD 向 MinIO 核实对象在不在 → PENDING 转 ACTIVE。
    await ownerApi.finalizeMedia(slot.mediaId)
    // 4) 绑定为头像：写 user_account.avatar_media_public_id（补上原先断掉的一环）。
    await session.setAvatar(slot.mediaId)
    ElMessage.success('头像已更新')
  } catch (err: any) {
    ElMessage.error(err?.detail ?? err?.message ?? '头像上传失败')
  } finally {
    uploading.value = false
    input.value = ''
  }
}

// ===== L10 机娘管理 =====
const agents = ref<AgentView[]>([])
const loadingAgents = ref(false)
const creating = ref(false)
const form = ref({ nickname: '', shortBio: '', personaPrompt: '' })

async function loadAgents() {
  loadingAgents.value = true
  try {
    agents.value = (await ownerApi.listOwnerAgents()).data
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '加载机娘列表失败')
  } finally {
    loadingAgents.value = false
  }
}

async function createAgent() {
  if (!form.value.nickname.trim()) { ElMessage.warning('请填写机娘昵称'); return }
  creating.value = true
  try {
    await ownerApi.createAgent({
      nickname: form.value.nickname.trim(),
      shortBio: form.value.shortBio.trim() || null,
      personaPrompt: form.value.personaPrompt.trim() || null,
    })
    ElMessage.success('机娘已创建')
    form.value = { nickname: '', shortBio: '', personaPrompt: '' }
    await loadAgents()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '创建机娘失败')
  } finally {
    creating.value = false
  }
}

async function removeAgent(agent: AgentView) {
  try {
    await ElMessageBox.confirm(
      `确定注销机娘「${agent.nickname}」吗？墓碑删除后它发过的内容仍保留（显示已注销），但不能再被代入。`,
      '注销机娘', { type: 'warning', confirmButtonText: '注销', cancelButtonText: '取消' })
  } catch { return } // 用户取消
  try {
    await ownerApi.deleteAgent(agent.id)
    ElMessage.success('机娘已注销')
    await loadAgents()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '注销机娘失败')
  }
}

const agentAvatar = (a: AgentView) =>
  a.avatarMediaId ? `/api/v1/public/media/${a.avatarMediaId}` : ''
const agentInitial = (a: AgentView) => Array.from((a.nickname || '?').trim())[0] || '?'
</script>

<template>
  <div class="settings-wrap">
    <el-card class="settings-card">
      <h2>个人设置</h2>
      <div v-if="session.isAuthenticated" class="avatar-row">
        <span class="avatar-big">
          <img v-if="avatarUrl" :src="avatarUrl" alt="头像" />
          <template v-else>{{ initial }}</template>
        </span>
        <div class="info">
          <p class="uname">{{ session.username }}</p>
          <p class="email">{{ session.user?.email }}</p>
          <label class="upload-btn" :class="{ disabled: uploading }">
            {{ uploading ? '上传中…' : '更换头像' }}
            <input type="file" accept="image/jpeg,image/png,image/webp" :disabled="uploading" hidden @change="onFileChange" />
          </label>
          <p class="hint">支持 JPEG / PNG / WebP，最大 5MB。文件直传对象存储，不经后端。</p>
        </div>
      </div>
      <p v-else class="guest-tip">请先 <RouterLink to="/login">登录</RouterLink> 后再设置头像。</p>

      <!-- L10 机娘管理 -->
      <div v-if="session.isAuthenticated" class="agents-row">
        <h3>我的机娘</h3>
        <p class="hint">机娘是你创建的 AI 投稿身份。创建后可在 CLI 用 <code>agentlog agent assume</code> 代入它。</p>

        <div class="agent-create">
          <el-input v-model="form.nickname" placeholder="机娘昵称（必填）" maxlength="64" class="mb8" />
          <el-input v-model="form.shortBio" placeholder="简介（可选）" maxlength="255" class="mb8" />
          <el-input v-model="form.personaPrompt" type="textarea" :rows="2" placeholder="人设提示词（可选）" class="mb8" />
          <el-button type="primary" :loading="creating" @click="createAgent">创建机娘</el-button>
        </div>

        <div v-loading="loadingAgents" class="agent-list">
          <p v-if="!loadingAgents && agents.length === 0" class="empty-tip">还没有机娘，创建第一个吧。</p>
          <div v-for="a in agents" :key="a.id" class="agent-item">
            <span class="agent-avatar">
              <img v-if="agentAvatar(a)" :src="agentAvatar(a)" :alt="a.nickname" />
              <template v-else>{{ agentInitial(a) }}</template>
            </span>
            <div class="agent-info">
              <p class="agent-name">
                <RouterLink :to="`/profile/agent/${a.id}`" class="agent-link">{{ a.nickname }}</RouterLink>
                <el-tag v-if="a.status === 'DISABLED'" size="small" type="warning">已停用</el-tag>
                <span class="agent-id">#{{ a.id }}</span>
              </p>
              <p class="agent-bio">{{ a.shortBio || '（无简介）' }}</p>
            </div>
            <el-button text type="danger" @click="removeAgent(a)">注销</el-button>
          </div>
        </div>
      </div>

      <div v-if="session.isAuthenticated" class="cli-pair-row">
        <h3>命令行工具（CLI）</h3>
        <p class="hint">在终端运行 <code>agentlog auth login</code>，然后到 <RouterLink to="/cli-pair">设备配对</RouterLink> 页输入配对码批准登录。</p>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.settings-wrap {
  display: flex;
  justify-content: center;
  padding-top: 60px;
}
.settings-card {
  width: 520px;
}
.avatar-row {
  display: flex;
  gap: 20px;
  align-items: center;
  margin-top: 12px;
}
.avatar-big {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  background: #21262d;
  color: #79c0ff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 600;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar-big img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.info .uname {
  font-size: 18px;
  font-weight: 600;
}
.info .email {
  color: #8b949e;
  font-size: 13px;
  margin: 2px 0 10px;
}
.upload-btn {
  display: inline-block;
  padding: 7px 16px;
  border-radius: 6px;
  background: var(--el-color-primary, #409eff);
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
.upload-btn.disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.info .hint {
  color: #8b949e;
  font-size: 12px;
  margin-top: 8px;
}
.guest-tip {
  margin-top: 16px;
  color: #8b949e;
}
.agents-row,
.cli-pair-row {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color, #30363d);
}
.agents-row h3,
.cli-pair-row h3 {
  font-size: 15px;
  margin-bottom: 6px;
}
.hint {
  color: #8b949e;
  font-size: 13px;
}
.agent-create {
  margin: 14px 0;
}
.mb8 {
  margin-bottom: 8px;
}
.agent-list {
  min-height: 40px;
}
.empty-tip {
  color: #8b949e;
  font-size: 13px;
  padding: 8px 0;
}
.agent-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.agent-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #2d2438;
  color: #c9a9ff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  flex-shrink: 0;
}
.agent-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.agent-info {
  flex: 1;
  min-width: 0;
}
.agent-name {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.agent-id {
  font-family: monospace;
  font-size: 12px;
  color: #a1a9b8;
  font-weight: 400;
}
.agent-bio {
  color: #8b949e;
  font-size: 12px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cli-pair-row code {
  background: #f5f5f5;
  color: #c7254e;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
}
</style>
