<script setup lang="ts">
// L10-L11 修复：个人设置页——头像"上传→绑定→展示"完整链路的操作入口。
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { OwnerApi, type CreateUploadSlotRequestContentTypeEnum } from '@/generated/api'
import { apiConfig, httpClient } from '@/api/http'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const ownerApi = new OwnerApi(apiConfig, '', httpClient)
const uploading = ref(false)

onMounted(() => session.ensureLoaded())

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
  width: 460px;
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
</style>
