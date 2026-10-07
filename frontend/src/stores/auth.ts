import { defineStore } from 'pinia'
import { http, type ApiUser } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: JSON.parse(sessionStorage.getItem('intelhub-user') || 'null') as ApiUser | null,
    accessToken: sessionStorage.getItem('intelhub-token') || '',
    loading: false,
  }),
  getters: {
    token: (state) => state.accessToken,
    isAdmin: (state) => state.user?.role === 'ADMIN',
  },
  actions: {
    async authenticate(form: Record<string, string>) {
      this.loading = true
      try {
        const user = await http.post('/auth/login', form) as ApiUser
        this.setUser(user)
      } finally {
        this.loading = false
      }
    },
    async register(form: Record<string, string>) {
      this.loading = true
      try {
        await http.post('/auth/register', form)
      } finally {
        this.loading = false
      }
    },
    async refresh() {
      if (!this.token) return
      const user = await http.get('/auth/me') as ApiUser
      this.user = user
      if (user.token) this.accessToken = user.token
      sessionStorage.setItem('intelhub-user', JSON.stringify(user))
      if (user.token) sessionStorage.setItem('intelhub-token', user.token)
    },
    async logout() {
      try { await http.post('/auth/logout') } finally { this.clear() }
    },
    setUser(user: ApiUser) {
      this.user = user
      if (user.token) {
        this.accessToken = user.token
        sessionStorage.setItem('intelhub-token', user.token)
      }
      sessionStorage.setItem('intelhub-user', JSON.stringify(user))
    },
    clear() {
      this.user = null
      this.accessToken = ''
      sessionStorage.removeItem('intelhub-token')
      sessionStorage.removeItem('intelhub-user')
    },
  },
})
