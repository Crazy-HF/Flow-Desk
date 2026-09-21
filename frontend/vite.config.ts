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
  css: {
    preprocessorOptions: {
      scss: {
        // 必须在 Element Plus 的 SCSS 之前注入：EP 的 light-x / dark-2 色阶由主色在编译期
        // 用 Sass mix() 算出，晚于 EP 生效就只能得到默认蓝色色阶。
        additionalData: `@use "@/styles/element/var-override.scss" as *;`,
      },
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
