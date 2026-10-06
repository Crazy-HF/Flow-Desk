import { expect, type Page } from '@playwright/test'

/**
 * 整页横向溢出量。
 *
 * <p>窄屏最常见的失败不是"难看"而是"整页被撑宽"：表格的 min-content 会把网格轨道顶出去。
 * 断言这个差值为 0，比肉眼看截图可靠。</p>
 */
export async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}

/**
 * 断言整页没有横向溢出，并容忍"刚换视口"的短暂过渡。
 *
 * <p>`page.setViewportSize({ width: 375 })` 返回时重排还没落地。2026-10-06 的稳定性复跑实测：
 * 分类管理页在切换视口后**立刻**测量得到 38px，100ms 及此后一直是 0，DOM 里也没有任何越界元素
 * ——这是测量竞态，不是版式缺陷。用轮询代替一次性测量后，真正的溢出仍会在超时后失败，
 * 只是不再把过渡帧判成缺陷。</p>
 */
export async function expectNoHorizontalOverflow(
  page: Page,
  message = '整页出现横向溢出',
): Promise<void> {
  await expect.poll(() => horizontalOverflow(page), { message }).toBe(0)
}
