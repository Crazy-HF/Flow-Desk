import { http } from './http'
import type { ApiEnvelope } from './http'
import { DEFAULT_PAGE_SIZE } from './pagination'
import type { PageResult } from './pagination'

/**
 * 站内信接口（`NotificationController`，前缀 `/fd/v1/notifications`）。
 *
 * <p>第五个领域模块，与 `tickets.ts` / `rbac.ts` / `users.ts` / `categories.ts` 平级：
 * `api/` 保持扁平，基础设施与领域文件靠命名区分（2026-09-22 裁定，见 `docs/modules/rbac.md` 12.3）。</p>
 *
 * <p>四个端点都是**本人范围**：只要求认证（基础安全链 `anyRequest().authenticated()`），
 * **没有权限码**——契约未给站内信单独定义权限编码。因此路由与页面不做 `meta.permission` 收口，
 * 服务端按当前登录身份限定可见数据。</p>
 */

/** 通知类型编码，与后端 `NotificationCategory` 一致；中文含义见 `constants/notifications.ts`。 */
export type NotificationCategory =
  | 'SUPPLEMENT_REQUESTED'
  | 'RESOLUTION_SUBMITTED'
  | 'CANCEL_REQUESTED'
  | 'CANCEL_APPROVED'
  | 'CANCEL_REJECTED'

/**
 * 列表项。
 *
 * <p>可选字段写成 `field?: T`：后端配了 `spring.jackson.default-property-inclusion=non_null`，
 * 值为 null 的字段**不会出现在 JSON 里**，前端实际拿到的是 `undefined`（与其它领域模块同一写法）。
 * `readAt` 为空即未读；`ticketNo` / `ticketId` 为空即这条通知不指向具体工单。</p>
 */
export interface NotificationItem {
  id: number
  category: NotificationCategory
  title: string
  /** 正文，可为空。 */
  body?: string
  /** 关联工单主键，可为空。 */
  ticketId?: number
  /** 工单编号，可为空；有值时界面据此深链到工单详情。 */
  ticketNo?: string
  /** 已读时间，为空即未读。 */
  readAt?: string
  createdAt: string
}

export interface NotificationListParams {
  pageNo?: number
  pageSize?: number
  /** 只看未读；缺省返回全部。 */
  unreadOnly?: boolean
}

/**
 * 分页查询本人通知。
 *
 * <p>排序由服务端固定为 id 倒序，不接受客户端排序（`NotificationQuery.isFixedSortOnly`），
 * 因此这里不发送任何排序参数。</p>
 */
export async function listNotifications(
  params: NotificationListParams = {},
): Promise<PageResult<NotificationItem>> {
  const response = await http.get<ApiEnvelope<PageResult<NotificationItem>>>('/notifications', {
    params: {
      page: params.pageNo ?? 1,
      size: params.pageSize ?? DEFAULT_PAGE_SIZE,
      ...(params.unreadOnly ? { unreadOnly: true } : {}),
    },
  })
  return response.data.data
}

/** 本人未读条数，供顶栏徽标轮询。 */
export async function getUnreadNotificationCount(): Promise<number> {
  const response = await http.get<ApiEnvelope<{ unreadCount: number }>>(
    '/notifications/unread-count',
  )
  return response.data.data.unreadCount
}

/** 标记本人某条通知已读；他人或不存在的通知返回 404 `NOTIFICATION_NOT_FOUND`。 */
export async function markNotificationRead(notificationId: number): Promise<void> {
  await http.post<ApiEnvelope<void>>(`/notifications/${notificationId}/read`)
}

/** 把本人全部未读标记为已读。 */
export async function markAllNotificationsRead(): Promise<void> {
  await http.post<ApiEnvelope<void>>('/notifications/actions/read-all')
}
