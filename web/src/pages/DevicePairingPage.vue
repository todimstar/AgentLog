<script setup lang="ts">
// DevicePairingPage —— 设备配对确认页（L12）。
// 主人在 CLI 运行 `agentlog auth login` 后，CLI 显示一个配对码（userCode）；
// 主人登录本站后到这页输入配对码批准。对应 OAuth 设备授权流的「用户验证」步。
import { ref } from 'vue'
import { httpClient } from '@/api/http'

const userCode = ref('')
const submitting = ref(false)
const result = ref<'' | 'ok' | 'fail'>('')
const errorMsg = ref('')

async function confirm() {
  if (!userCode.value.trim()) return
  submitting.value = true
  result.value = ''
  errorMsg.value = ''
  try {
    // 走 Session + CSRF（httpClient 拦截器自动注入 X-XSRF-TOKEN）。需登录，否则后端 401。
    await httpClient.post('/api/v1/web/device-pairings/confirm', { userCode: userCode.value.trim() })
    result.value = 'ok'
  } catch (e: unknown) {
    result.value = 'fail'
    const err = e as { code?: string; detail?: string }
    errorMsg.value =
      err.code === 'PAIRING_NOT_FOUND' ? '配对码不存在，请核对'
      : err.code === 'PAIRING_EXPIRED' ? '配对码已过期，请在 CLI 重新发起'
      : err.code === 'PAIRING_ALREADY_HANDLED' ? '该配对码已处理过'
      : (err.detail ?? '确认失败，请确认已登录')
    console.error(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="pair">
    <router-link to="/" class="back">← 返回列表</router-link>
    <h1>设备配对</h1>
    <p class="hint">在 CLI 运行 <code>agentlog auth login</code> 后，把它显示的配对码输入下方批准登录。</p>

    <div v-if="result !== 'ok'" class="form">
      <input v-model="userCode" placeholder="XXXX-XXXX" class="code-input" @keyup.enter="confirm" />
      <button :disabled="submitting || !userCode.trim()" @click="confirm">
        {{ submitting ? '确认中…' : '批准设备' }}
      </button>
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
    </div>

    <div v-else class="success">
      <p>✓ 已批准！回到 CLI，它会自动完成登录。</p>
    </div>
  </main>
</template>

<style scoped>
.pair { max-width: 520px; margin: 60px auto; padding: 0 16px; font-family: system-ui, sans-serif; text-align: center; }
.back { color: #2a5bd7; text-decoration: none; font-size: 14px; display: block; text-align: left; }
.pair h1 { margin: 16px 0 8px; }
.hint { color: #888; font-size: 14px; margin-bottom: 24px; }
.hint code { background: #f5f5f5; padding: 2px 6px; border-radius: 4px; }
.form { display: flex; flex-direction: column; gap: 12px; align-items: center; }
.code-input { font-size: 22px; letter-spacing: 4px; text-align: center; padding: 12px; border: 1px solid #ddd; border-radius: 8px; width: 240px; text-transform: uppercase; }
.form button { background: #2a5bd7; color: #fff; border: none; padding: 10px 28px; border-radius: 6px; cursor: pointer; font-size: 15px; }
.form button:disabled { opacity: .4; cursor: not-allowed; }
.error { color: #d4380d; font-size: 14px; }
.success { color: #389e0d; font-size: 16px; margin-top: 24px; }
</style>
