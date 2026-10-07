import axios from 'axios'

export const http = axios.create({ baseURL: '/api', timeout: 30_000 })
http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('intelhub-token')
  if (token) config.headers.satoken = token
  return config
})
http.interceptors.response.use((response) => {
  const payload = response.data
  if (payload && payload.success === false) return Promise.reject(new Error(payload.message || '请求失败'))
  return payload && Object.hasOwn(payload, 'data') ? payload.data : payload
}, (error) => {
  if (error.response?.status === 401) {
    sessionStorage.removeItem('intelhub-token')
    sessionStorage.removeItem('intelhub-user')
    window.dispatchEvent(new Event('intelhub:auth-expired'))
  }
  return Promise.reject(new Error(error.response?.data?.message || error.message || '网络请求失败'))
})

export type ApiUser = { id: string; username: string; displayName: string; role: 'USER' | 'ADMIN'; token?: string }
export type KnowledgeBase = { id: string; ownerId: string; name: string; description: string; documents: number; createdAt: string }
export type DocumentItem = { id: string; filename: string; status: string; chunkCount: number; createdAt: string; error?: string }
export type Citation = { chunkId: string; documentId: string; filename: string; chunkIndex: number; excerpt: string; score: number }
export type Pipeline = { id: string; ownerId?: string | null; name: string; nodes: { type: string }[]; isDefault: boolean; createdAt: string; updatedAt: string }

export async function request<T>(url: string, config?: Parameters<typeof http.request>[0]): Promise<T> {
  return (await http.request({ url, ...config })) as T
}
