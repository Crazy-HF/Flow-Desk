package com.flowdesk.notification.domain;

/**
 * 站内信类型：与产生通知的业务动作一一对应，落库为字符串编码，库层用 CHECK 约束固定。
 *
 * <p>新增类型必须同时改 {@code ck_notification_category}（见 {@code docs/database-design.md}）。</p>
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
    CANCEL_REJECTED
}
