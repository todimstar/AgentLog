import { defineConfig } from "vite";
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
  }
});
