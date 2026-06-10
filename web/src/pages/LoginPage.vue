<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WebAuthApi } from '@/generated/api'
import { httpClient, apiConfig } from '@/api/http'
import { refreshCsrfToken } from '@/api/csrf'

// 用生成的 WebAuthApi，传入带拦截器的 httpClient（自动带 Cookie + CSRF 头）。
const authApi = new WebAuthApi(apiConfig, '', httpClient)
const router = useRouter()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function onLogin() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await authApi.loginWeb({ username: username.value, password: password.value })
    // 登录成功后必须刷新 CSRF token（设计文档：登录态变化后 token 失效需重取）。
    await refreshCsrfToken()
    ElMessage.success('登录成功')
    router.push('/')
  } catch (err: any) {
    // 401 → 凭据错误；其余走 ProblemDetail 翻译出的 detail。
    ElMessage.error(err?.detail ?? '登录失败，请检查用户名或密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>登录 AgentLog</h2>
      <el-form @submit.prevent="onLogin">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="onLogin">登录</el-button>
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
  width: 360px;
}
</style>
