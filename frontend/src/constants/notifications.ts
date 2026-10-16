import type { NotificationCategory } from '@/api/notifications'

/**
 * 站内信领域的展示映射。
 *
 * <p>与 `authorization.ts` / `tickets.ts` 同一层：编码到中文的对照只写一次，页面只负责排版。
 * 编码来自后端 `NotificationCategory`（库层用 `ck_notification_category` 固定），
 * 这里不新增前端自造的取值。</p>
 */
export const notificationCategoryLabels: Record<NotificationCategory, string> = {
  SUPPLEMENT_REQUESTED: '补充请求',
  RESOLUTION_SUBMITTED: '已提交解决结果',
  CANCEL_REQUESTED: '有待批准的撤销请求',
  CANCEL_APPROVED: '撤销请求已批准',
  CANCEL_REJECTED: '撤销请求被拒绝',
}

/**
 * 编码到中文名；未收录的编码按原样返回，不丢掉（丢掉会让一条通知看起来"什么也没发生"）。
 * 与 `ticketRecordTypeLabel` 同一写法：入参是 `string`，因为库里的编码可能早于前端发布。
 */
export function notificationCategoryLabel(category: string): string {
  return notificationCategoryLabels[category as NotificationCategory] ?? category
}
