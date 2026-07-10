<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const mode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const username = ref('')
const verCode = ref('')
const loading = ref(false)

// 发送验证码倒计时：前端 60s 体验层防连点（后端另有 10min Redis 防刷，两者独立）。
const countdown = ref(0)
let timer: number | undefined
const emailLooksValid = computed(() => /\S+@\S+\.\S+/.test(email.value))
const canSendCode = computed(() => countdown.value === 0 && emailLooksValid.value)

function startCountdown() {
  countdown.value = 60
  timer = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0 && timer) { clearInterval(timer); timer = undefined }
  }, 1000)
}
onUnmounted(() => { if (timer) clearInterval(timer) })

function redirectAfterAuth() {
  const raw = route.query.redirect
  const to = typeof raw === 'string' && raw.startsWith('/') ? raw : '/'
  router.push(to)
}

async function onSendCode() {
  if (!canSendCode.value) { ElMessage.warning('请先填写正确的邮箱'); return }
  try {
    await session.sendRegisterCode(email.value)
    ElMessage.success('验证码已发送，请查收邮箱（开发环境看后端日志）')
    startCountdown()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '验证码发送失败')
  }
}

async function onLogin() {
  if (!email.value || !password.value) { ElMessage.warning('请输入邮箱和密码'); return }
  loading.value = true
  try {
    await session.login(email.value, password.value)
    ElMessage.success('登录成功')
    redirectAfterAuth()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '登录失败，请检查邮箱或密码')
  } finally {
    loading.value = false
  }
}

async function onRegister() {
  if (!email.value || !username.value || !password.value || !verCode.value) {
    ElMessage.warning('请填写完整（邮箱 / 验证码 / 用户名 / 密码）'); return
  }
  loading.value = true
  try {
    await session.register(email.value, username.value, password.value, verCode.value)
    // 注册只建账号，随即用同一凭据登录建立会话（Session 由登录建立，不是注册）。
    await session.login(email.value, password.value)
    ElMessage.success('注册成功，已自动登录')
    redirectAfterAuth()
  } catch (err: any) {
    ElMessage.error(err?.detail ?? '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="mode-tabs">
        <button type="button" :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button type="button" :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>

      <!-- 登录：邮箱 + 密码 -->
      <el-form v-if="mode === 'login'" @submit.prevent="onLogin">
        <el-form-item label="邮箱">
          <el-input v-model="email" placeholder="you@example.com" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="onLogin">登录</el-button>
      </el-form>

      <!-- 注册：邮箱 → 验证码 → 用户名 + 密码 -->
      <el-form v-else @submit.prevent="onRegister">
        <el-form-item label="邮箱">
          <el-input v-model="email" placeholder="you@example.com" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="verCode" placeholder="6 位验证码" maxlength="6" />
            <el-button :disabled="!canSendCode" @click="onSendCode">
              {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="展示用户名（唯一、到处显示）" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="至少 8 位" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="onRegister">注册并登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  display: flex;
  justify-content: center;
  padding-top: 80px;
}
.login-card {
  width: 380px;
}
.mode-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.mode-tabs button {
  flex: 1;
  padding: 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 15px;
  color: #8b949e;
  border-bottom: 2px solid transparent;
}
.mode-tabs button.active {
  color: var(--el-color-primary, #409eff);
  border-bottom-color: currentColor;
  font-weight: 600;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.code-row .el-input {
  flex: 1;
}
</style>
