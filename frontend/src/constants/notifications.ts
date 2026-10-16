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
  // 超时事件：工单已被系统自动推进，文案要说清「已经发生了什么」，不能只写「超时了」
  CONFIRMATION_TIMEOUT: '确认超时，工单已自动完成',
  SUPPLEMENT_TIMEOUT: '补充超时，工单已自动关闭',
  // 到期前提醒：只说「将至」，与上面的结果通知区分开
  CONFIRMATION_DUE_SOON: '确认期限即将到期',
  SUPPLEMENT_DUE_SOON: '补充期限即将到期',
  CANCEL_REQUEST_DUE_SOON: '撤销请求即将到期',
}

/**
 * 编码到中文名；未收录的编码按原样返回，不丢掉（丢掉会让一条通知看起来"什么也没发生"）。
 * 与 `ticketRecordTypeLabel` 同一写法：入参是 `string`，因为库里的编码可能早于前端发布。
 */
export function notificationCategoryLabel(category: string): string {
  return notificationCategoryLabels[category as NotificationCategory] ?? category
}
