import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/fd': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
    // e2e 与本地预览走的是 preview 服务器，同样需要把 /fd 代理到后端，否则接口会 404
    proxy: {
      '/fd': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
