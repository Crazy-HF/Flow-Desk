-- 通知类型扩容：超时事件 + 到期前提醒（完整版 backlog 3「两个超时自动任务与到期前提醒」）。
--
-- 设计要点：
--   * 只替换 ck_notification_category 的取值集合，不新增表 / 列 / 索引 / 外键
--     （外键总数保持 25、表与索引集合不变，DatabaseMigrationIT 的基线断言不受影响）；
--     约束名保持 ck_notification_category，scripts/notification-channel-acceptance.ps1
--     与 docs/database-design.md 19.6 的引用无需改指向；
--   * DROP CHECK 与 ADD CONSTRAINT 写在同一条 ALTER 里：MySQL 8.4.11 实测通过，
--     拆成两条会在两条语句之间留下「无约束」窗口，故不采用；
--   * 新增 5 个编码分两类，去重都靠 uk_notification_recipient_dedup(recipient_id, dedup_key)：
--       ① 超时事件 —— 工单被系统自动推进后，提交人与最后负责人各发一条（同一 key、不同收件人），
--          dedup_key = {类别}:{ticketId}:{endedAt 毫秒}，同一次超时只发一次：
--            CONFIRMATION_TIMEOUT   待确认超时 → 工单自动完成
--            SUPPLEMENT_TIMEOUT     待补充超时 → 工单自动关闭
--       ② 到期前提醒 —— 只发给该等待的唯一行动人，
--          dedup_key = {类别}:{ticketId}:{期限毫秒}；期限被改写 = 新 key = 允许再提醒一次：
--            CONFIRMATION_DUE_SOON      提交人确认期限将至
--            SUPPLEMENT_DUE_SOON        提交人补充期限将至
--            CANCEL_REQUEST_DUE_SOON    当前负责人撤销请求期限将至
--   * 口径变更（取代 V10 头注释里的「未读永不清理」；V10 已发布，注释不回改）：
--     清理任务改为 ①「已读」按 read_at 早于保留期删除（原口径，不变）；
--     ②「未读」按 created_at 早于未读保留期（默认 3 天）删除。
--     口径正文随本批落地，见 docs/database-design.md 19.6 与 application.yml 的 flowdesk.notification 段。
ALTER TABLE notification
    DROP CHECK ck_notification_category,
    ADD CONSTRAINT ck_notification_category CHECK (category IN (
        'SUPPLEMENT_REQUESTED', 'RESOLUTION_SUBMITTED',
        'CANCEL_REQUESTED', 'CANCEL_APPROVED', 'CANCEL_REJECTED',
        'CONFIRMATION_TIMEOUT', 'SUPPLEMENT_TIMEOUT',
        'CONFIRMATION_DUE_SOON', 'SUPPLEMENT_DUE_SOON', 'CANCEL_REQUEST_DUE_SOON'));
