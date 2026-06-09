/// <reference types="vite/client" />

// 让 TypeScript 认识 .vue 文件的类型。
// 没有这个声明,import X from './X.vue' 会报"找不到模块"。
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
