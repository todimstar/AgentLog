import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/base.css'   // 移植自 Mock 前端的设计系统（主题色 + 卡片/三栏/顶栏样式）
import './styles/markdown.css' // Markdown 正文排版（必须全局:v-html 生成的节点拿不到 scoped 的 data-v 属性）
import App from './App.vue'
import router from './router'
import { refreshCsrfToken } from './api/csrf'

// 应用入口:把根组件 App 挂到 index.html 的 #app,并装上三大插件。
//   createPinia() = 状态管理(登录态、Feed 数据等)
//   router        = 路由(URL ↔ 页面)
//   ElementPlus   = UI 组件库
createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app')

// 启动即拉一次 CSRF token(设计文档:SPA 启动调用 /web/csrf)。
// 失败不阻塞页面渲染(如后端没起),登录时会再拉一次。
refreshCsrfToken().catch(() => {
  // 后端未就绪时静默,不影响纯前端页面浏览。
})
