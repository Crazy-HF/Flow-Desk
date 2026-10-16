package com.flowdesk.notification.domain;

/**
 * 站内信类型：与产生通知的业务动作一一对应，落库为字符串编码，库层用 CHECK 约束固定。
 *
 * <p>新增类型必须同时改 {@code ck_notification_category}（迁移 {@code V11}，见 {@code docs/database-design.md}）、
 * 前端 {@code frontend/src/api/notifications.ts} 的联合类型与
 * {@code frontend/src/constants/notifications.ts} 的中文对照，四处保持同一集合。</p>
 */
public enum NotificationCategory {

    /** IT 向提交人发出补充请求。 */
    SUPPLEMENT_REQUESTED,
    /** IT 提交解决结果，等待提交人确认。 */
    RESOLUTION_SUBMITTED,
    /** 提交人发起撤销请求，等待当前负责人批准或拒绝。 */
    CANCEL_REQUESTED,
    /** 当前负责人批准撤销请求，工单进入已取消。 */
    CANCEL_APPROVED,
    /** 当前负责人拒绝撤销请求，工单保留原状态。 */
    CANCEL_REJECTED,

    // ---- 超时事件：工单被系统自动推进（收件人 = 提交人 + 最后负责人）----

    /** 待确认超时，工单被自动完成。 */
    CONFIRMATION_TIMEOUT,
    /** 待补充超时，工单被自动关闭。 */
    SUPPLEMENT_TIMEOUT,

    // ---- 到期前提醒：等待即将到期（收件人 = 该等待的唯一行动人）----

    /** 确认期限将至，提醒提交人尽快确认。 */
    CONFIRMATION_DUE_SOON,
    /** 补充期限将至，提醒提交人尽快提交补充。 */
    SUPPLEMENT_DUE_SOON,
    /** 撤销请求期限将至，提醒当前负责人尽快裁决。 */
    CANCEL_REQUEST_DUE_SOON
}
