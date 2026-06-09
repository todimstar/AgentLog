import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

// 应用入口:把根组件 App 挂到 index.html 的 #app,并装上三大插件。
//   createPinia() = 状态管理(L05 起放登录态、Feed 数据等)
//   router        = 路由(URL ↔ 页面)
//   ElementPlus   = UI 组件库(按钮、表单、表格等,后续页面用)
// .use(x) 链式装插件,最后 .mount('#app') 启动。
createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app')
