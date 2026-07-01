import { defineStore } from 'pinia'
import { WebAuthApi } from '@/generated/api'
import type { UserView } from '@/generated/api'
import { apiConfig, httpClient } from '@/api/http'
import { refreshCsrfToken } from '@/api/csrf'

const authApi = new WebAuthApi(apiConfig, '', httpClient)

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as UserView | null,
    loading: false,
  }),
  getters: {
    isAuthenticated: (state) => state.user !== null,
    displayName: (state) => state.user?.displayName ?? '游客',
  },
  actions: {
    async refresh() {
      this.loading = true
      try {
        this.user = (await authApi.getCurrentUser()).data
      } catch (err: any) {
        if (err?.response?.status === 401) this.user = null
        else throw err
      } finally {
        this.loading = false
      }
    },
    async login(username: string, password: string) {
      this.user = (await authApi.loginWeb({ username, password })).data
      await refreshCsrfToken()
    },
    async logout() {
      try {
        await authApi.logoutWeb()
      } finally {
        this.user = null
        await refreshCsrfToken()
      }
    },
  },
})
