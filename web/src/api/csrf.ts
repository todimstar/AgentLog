import axios from 'axios'

// CSRF 处理：启动/登录后/登出后调用 /api/v1/web/csrf，
// 后端会种下 JS 可读的 XSRF-TOKEN Cookie 并返回 token + headerName。
// 我们把 token 记在内存，供 http.ts 的请求拦截器附到 unsafe 请求的 Header 上。

let csrfToken = ''
let csrfHeaderName = 'X-XSRF-TOKEN'

export function getCsrfToken(): string {
  return csrfToken
}

export function getCsrfHeaderName(): string {
  return csrfHeaderName
}

/** 拉取最新 CSRF token。应在应用启动、登录成功、登出成功后各调一次（token 随会话变化）。 */
export async function refreshCsrfToken(): Promise<void> {
  const { data } = await axios.get('/api/v1/web/csrf', { withCredentials: true })
  csrfToken = data.token
  csrfHeaderName = data.headerName
}
