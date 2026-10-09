-- 两阶段撤销（docs/kickoff.md 4.7 未来方向、docs/implementation-plan.md 9.3 决策记录）：
-- 待批准撤销请求用 ticket 上的一组可空列表达，不新增状态——状态仍只有 7 个。
-- 与 close_reason / completion_method 同一模式：当前快照表保留当前态字段，事件本身进 ticket_record。
ALTER TABLE ticket
    ADD COLUMN cancel_requested_at        DATETIME(3)   NULL COMMENT '待批准撤销请求的发起时间；为空表示没有待决请求',
    ADD COLUMN cancel_request_reason      VARCHAR(1000) NULL COMMENT '待批准撤销请求的说明，进入终态时随请求一并清空',
    ADD COLUMN cancel_request_deadline_at DATETIME(3)   NULL COMMENT '待批准撤销请求的响应期限；仅写入并展示，到期不自动处置';

-- 三列同生同灭：只写了时间没写说明这类半截状态在库层面就进不去
ALTER TABLE ticket
    ADD CONSTRAINT ck_ticket_cancel_request_pair CHECK (
        (cancel_requested_at IS NULL
            AND cancel_request_reason IS NULL
            AND cancel_request_deadline_at IS NULL)
        OR (cancel_requested_at IS NOT NULL
            AND cancel_request_reason IS NOT NULL
            AND cancel_request_deadline_at IS NOT NULL));

-- 待决请求只能挂在「有人负责且未终结」的三个状态上：待受理没有负责人，走直接撤销；
-- 终态不可能有待决请求——closeManually 与 confirmResolution 因此必须一并清空请求三列。
ALTER TABLE ticket
    ADD CONSTRAINT ck_ticket_cancel_request_status CHECK (
        cancel_requested_at IS NULL
        OR status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION'));

-- 记录类型扩 4 个：发起、批准、拒绝、撤回请求；CANCELLATION 保留给「待受理直接撤销」。
-- 已发布的历史迁移不得修改，因此在同一条 ALTER 里重建同名检查约束，不做成两条语句。
ALTER TABLE ticket_record
    DROP CHECK ck_ticket_record_type,
    ADD CONSTRAINT ck_ticket_record_type CHECK (record_type IN
        ('CREATE', 'CLAIM', 'PROCESS', 'CATEGORY_CHANGE', 'PRIORITY_CHANGE', 'TRANSFER',
         'ADMIN_HANDOFF', 'SUPPLEMENT_REQUEST', 'REQUESTER_SUPPLEMENT', 'SUPPLEMENT_REQUEST_WITHDRAWN',
         'RESOLUTION', 'UNSATISFIED_FEEDBACK', 'COMPLETION', 'CANCELLATION', 'CLOSURE',
         'CANCELLATION_REQUEST', 'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED',
         'CANCELLATION_REQUEST_WITHDRAWN'));
