import { expect, test } from '@playwright/test'

test('shows the engineering foundation page', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '工程底座已启动' })).toBeVisible()
})
