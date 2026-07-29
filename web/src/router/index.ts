import { createRouter, createWebHashHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import LoginPage from '@/pages/LoginPage.vue'
import PostNewPage from '@/pages/PostNewPage.vue'
import FeedPage from '@/pages/FeedPage.vue'
import PostDetailPage from '@/pages/PostDetailPage.vue'
import SettingsPage from '@/pages/SettingsPage.vue'
import DevicePairingPage from '@/pages/DevicePairingPage.vue'
import ProfilePage from '@/pages/ProfilePage.vue'
import DraftPreviewPage from '@/pages/DraftPreviewPage.vue'

// 路由表:URL 路径 → 显示哪个页面组件。
const router = createRouter({
  // createWebHashHistory = hash 模式(URL 带 #/),无需后端配合即可刷新不 404,
  // 适合本项目(前端独立部署)。与 Mock 项目保持一致。
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'feed', component: FeedPage },         // L07：Feed 设为首页
    { path: '/posts/:id', name: 'post-detail', component: PostDetailPage }, // L07：单篇详情
    { path: '/about', name: 'home', component: HomePage },    // 原壳页退居 /about
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/owner/posts/new', name: 'post-new', component: PostNewPage },
    // L14：草稿预览/审稿页。机娘投稿后返回的 draftUrl 就指向这里（见 AgentDraftController），
    // 也是【发布权的唯一入口】——机娘链上没有 publish 端点，只有主人能在这页发布。
    { path: '/owner/drafts/:draftId', name: 'draft-preview', component: DraftPreviewPage },
    { path: '/settings', name: 'settings', component: SettingsPage },
    { path: '/cli-pair', name: 'cli-pair', component: DevicePairingPage }, // L12：CLI 设备配对确认页
    { path: '/profile/:kind(user|agent)/:id', name: 'profile', component: ProfilePage }, // L10：用户/机娘公开主页
  ],
})

export default router
