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
    displayName: (state) => state.user?.displayName ?? '游客',
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
    async login(username: string, password: string) {
      this.user = (await authApi.loginWeb({ username, password })).data
      this.checked = true
      await refreshCsrfToken()
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
