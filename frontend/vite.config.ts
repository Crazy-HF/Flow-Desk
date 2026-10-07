import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * 后端基址默认仍是本机 8081；`FLOWDESK_API_TARGET` 可覆盖它。
 *
 * <p>存在的理由是一次真实场景：本机 8081 上跑着用户自己启动的后端（旧代码），
 * 而验收需要跑"刚编译出来"的后端，只能并行走另一个端口。把代理目标写死会让
 * E2E 只能验旧代码，或者被迫去停用户的进程。</p>
 */
const apiTarget = process.env.FLOWDESK_API_TARGET ?? 'http://localhost:8081'

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
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
    // e2e 与本地预览走的是 preview 服务器，同样需要把 /fd 代理到后端，否则接口会 404
    proxy: {
      '/fd': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
})
