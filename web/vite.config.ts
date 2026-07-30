// defineConfig 从 vitest/config 取(它是 vite 那个的超集,多认一个 test 字段)，
// 这样测试配置能和构建配置共用同一份别名/插件，不必再维护第二个 vitest.config.ts。
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 让 vite/rollup 构建时也认识 @ 别名(tsconfig 的 paths 只管类型检查，不管打包）。
      // Vite+TS 项目必须双配置：tsconfig.app.json 配一份给 TS，这里配一份给构建。
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true
      }
    }
  },
  test: {
    // ★ 必须是 jsdom 而不是默认的 node：DOMPurify 消毒的是【真 DOM 节点】,
    //   它把 HTML 丢进一个 DOM 解析器再逐节点过滤白名单——没有 window/document 就跑不起来。
    //   这也正是它绕不过畸形标签的原因(浏览器怎么解析,它就怎么看)。
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
  }
});
