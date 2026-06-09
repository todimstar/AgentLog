import { createRouter, createWebHashHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'

// 路由表:URL 路径 → 显示哪个页面组件。
// L04 只放一个首页,证明路由系统装好了;后续课程往这里加 /feed、/posts/:id 等。
const router = createRouter({
  // createWebHashHistory = hash 模式(URL 带 #/),无需后端配合即可刷新不 404,
  // 适合本项目(前端独立部署)。与 Mock 项目保持一致。
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomePage },
  ],
})

export default router
