import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  // 串行执行：一次登录要跑 Argon2id（内存 19 MiB、迭代 2），并发登录会把 CPU 抢满，
  // 反而把每次登录从约 2 秒拖到十几秒，导致超时假失败。
  workers: 1,
  // 同理放宽超时：Argon2 校验是刻意做慢的，界面等待时间与机器性能强相关。
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    // 需要先执行 pnpm build；CI 与本地都按"先构建再测试"的顺序调用
    command: 'pnpm preview --host 127.0.0.1',
    port: 4173,
    reuseExistingServer: !process.env.CI,
  },
})
