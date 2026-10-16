-- 站内信（应用内通知通道）。
--
-- 设计要点：
--   * 通知是「发给某个用户的一条消息」，只按收件人本人范围读写，不参与跨用户可见性；
--   * ticket_id 用限制删除外键保留与工单的引用完整性，ticket_no 冗余保存编号，
--     收件箱列表因此不需要 join ticket；
--   * dedup_key 为「同一工单 + 同一期限」的提醒去重预留（唯一键按收件人隔离；
--     MySQL 允许多个 NULL，普通通知不填这一列，不受唯一键影响）；
--   * read_at 为空即未读；清理任务只删除「已读且 read_at 早于保留期」的行，未读永不清理。
CREATE TABLE notification (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT UNSIGNED NOT NULL COMMENT '收件人（工单提交人或当前负责人）',
    category     VARCHAR(32)     NOT NULL COMMENT '通知类型（固定枚举，与业务动作一一对应）',
    title        VARCHAR(200)    NOT NULL COMMENT '通知标题',
    body         VARCHAR(1000)   NULL COMMENT '通知正文',
    ticket_id    BIGINT UNSIGNED NULL COMMENT '关联工单',
    ticket_no    VARCHAR(32)     NULL COMMENT '冗余保存的工单编号，供收件箱直接展示与深链',
    dedup_key    VARCHAR(128)    NULL COMMENT '提醒去重键（同一工单 + 同一期限），普通通知为空',
    read_at      DATETIME(3)     NULL COMMENT '已读时间；为空即未读',
    created_at   DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_recipient_dedup UNIQUE (recipient_id, dedup_key),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES iam_user (id),
    CONSTRAINT fk_notification_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT ck_notification_category CHECK (category IN (
        'SUPPLEMENT_REQUESTED', 'RESOLUTION_SUBMITTED',
        'CANCEL_REQUESTED', 'CANCEL_APPROVED', 'CANCEL_REJECTED')),
    -- 未读列表与未读计数：收件人定值 + read_at IS NULL + 按 id 倒序
    INDEX idx_notification_recipient_read (recipient_id, read_at, id),
    -- 清理任务：按 read_at 范围删除已读通知
    INDEX idx_notification_read_at (read_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内信（应用内通知）';
