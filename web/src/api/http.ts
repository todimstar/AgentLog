import { Configuration } from '@/generated/api'

// ── 手写薄封装层 ───────────────────────────────────────────────
// 生成代码(generated/)负责"接口长什么样",这一层负责"调用时的横切逻辑":
// 统一的 baseURL、withCredentials、未来的 CSRF 头与 ProblemDetail 错误处理。
// 设计文档要求:页面禁止手拼 URL，一律通过生成的 Api 类 + 这份配置发请求。

// OpenAPI 生成的每个 Api 类(PublicApi 等)都接受一个 Configuration。
// 这里集中配置一次，供全应用复用。
export const apiConfig = new Configuration({
  // basePath 留空 = 用相对路径 /api/...，由 vite 代理转发到后端 8080(见 vite.config.ts)。
  // 生产环境同源部署时同样走相对路径，无需改代码。
  basePath: '',
})

// 说明(TS/Java 对照):
//   import { Configuration } from '...'  ≈ Java 的 import，只是 TS 用花括号做"具名导入"。
//   export const x = ...                 ≈ 暴露一个公共常量给别的模块用。
//   CSRF token 注入、401/403 拦截、ProblemDetail 翻译将在 L05 接入登录时补到这一层。
