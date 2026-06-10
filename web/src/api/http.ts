import axios, { type AxiosInstance } from 'axios'
import { Configuration } from '@/generated/api'
import { getCsrfToken, getCsrfHeaderName } from './csrf'

// ── 手写薄封装层 ───────────────────────────────────────────────
// 生成代码(generated/)负责"接口长什么样"，这一层负责"调用时的横切逻辑"：
// withCredentials(带 Session Cookie)、CSRF 头注入、ProblemDetail 错误翻译。
// 设计文档：页面禁止手拼 URL，一律通过生成的 Api 类 + 这份配置发请求。

// 共享的 axios 实例：所有请求都经过它的拦截器。
export const httpClient: AxiosInstance = axios.create({
  // 相对路径 /api/...，由 vite 代理转发到后端 8080(见 vite.config.ts)。
  baseURL: '',
  // 关键：带上 Cookie(JSESSIONID + XSRF-TOKEN)。Session 认证全靠它。
  withCredentials: true,
})

// 请求拦截器：给 unsafe 方法(POST/PUT/PATCH/DELETE)自动附加 CSRF 头。
// GET 等 safe 方法不需要(CSRF 只防会改数据的请求)。
httpClient.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (['post', 'put', 'patch', 'delete'].includes(method)) {
    config.headers.set(getCsrfHeaderName(), getCsrfToken())
  }
  return config
})

// 响应拦截器：把后端 RFC 9457 ProblemDetail 错误统一翻译成一致的 Error，方便页面 catch。
httpClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const problem = error.response?.data
    if (problem && typeof problem === 'object' && 'code' in problem) {
      // 把 ProblemDetail 的关键信息抽出来挂到 error 上，页面可读 error.code / error.detail。
      error.code = problem.code
      error.detail = problem.detail
      error.problemTitle = problem.title
    }
    return Promise.reject(error)
  },
)

// 生成的 Api 类(PublicApi 等)接受 (Configuration, basePath, axiosInstance)。
// 传入上面的 httpClient，让生成代码也走我们的拦截器。
export const apiConfig = new Configuration({ basePath: '' })
