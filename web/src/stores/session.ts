import { defineStore } from 'pinia'
import { WebAuthApi } from '@/generated/api'
import type { UserView } from '@/generated/api'
import { apiConfig, httpClient } from '@/api/http'
import { refreshCsrfToken } from '@/api/csrf'

const authApi = new WebAuthApi(apiConfig, '', httpClient)
let refreshPromise: Promise<void> | null = null

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as UserView | null,
    loading: false,
    checked: false,
  }),
  getters: {
    isAuthenticated: (state) => state.user !== null,
    username: (state) => state.user?.username ?? '游客',
  },
  actions: {
    async refresh() {
      if (refreshPromise) return refreshPromise
      this.loading = true
      refreshPromise = (async () => {
        try {
          this.user = (await authApi.getCurrentUser()).data
        } catch (err: any) {
          if (err?.response?.status === 401) this.user = null
          else throw err
        } finally {
          this.checked = true
          this.loading = false
          refreshPromise = null
        }
      })()
      return refreshPromise
    },
    async ensureLoaded() {
      if (this.checked) return
      await this.refresh()
    },
    async sendRegisterCode(email: string) {
      await authApi.sendRegisterCode({ email })
    },
    async register(email: string, username: string, password: string, verCode: string) {
      // 注册只建账号、不建会话；成功后由调用方再 login 拿会话（Session 由登录建立）。
      await authApi.registerUser({ email, username, password, verCode })
    },
    async login(email: string, password: string) {
      this.user = (await authApi.loginWeb({ email, password })).data
      this.checked = true
      await refreshCsrfToken()
    },
    async setAvatar(mediaId: string) {
      // 头像上传收尾：把已 finalize 的媒体绑为头像，后端回带新 avatarMediaId 的 UserView。
      this.user = (await authApi.setMyAvatar({ mediaId })).data
    },
    async logout() {
      try {
        await authApi.logoutWeb()
      } finally {
        this.user = null
        this.checked = true
        await refreshCsrfToken()
      }
    },
  },
})
