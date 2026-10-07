import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    // 与 vue-cli 默认端口保持一致
    port: 8080,
  },
  build: {
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        // 拆分大包，避免单个 chunk 过大
        manualChunks: {
          monaco: ["monaco-editor"],
          arco: ["@arco-design/web-vue"],
        },
      },
    },
  },
});
