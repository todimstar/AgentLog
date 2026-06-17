import { createRouter, createWebHashHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import LoginPage from '@/pages/LoginPage.vue'
import PostNewPage from '@/pages/PostNewPage.vue'

// 路由表:URL 路径 → 显示哪个页面组件。
const router = createRouter({
  // createWebHashHistory = hash 模式(URL 带 #/),无需后端配合即可刷新不 404,
  // 适合本项目(前端独立部署)。与 Mock 项目保持一致。
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomePage },
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/owner/posts/new', name: 'post-new', component: PostNewPage },
  ],
})

export default router
