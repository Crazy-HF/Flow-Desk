-- 撤销请求到期即失效（docs/kickoff.md 4.7、docs/implementation-plan.md 9.3 待改清单 ①～③）：
-- 期限届满后批准 / 拒绝 / 撤回三条路一起关闭，工单保持原状态、不自动取消；提交人可以重新发起，
-- 新请求直接覆盖已过期的那一份（覆盖时补写一条「已过期」记录），且不设终身次数上限。
--
-- 记录类型扩 1 个：CANCELLATION_REQUEST_EXPIRED（原请求被新请求替换时留痕）。
-- 已发布的历史迁移不得修改，因此在同一条 ALTER 里重建同名检查约束（与 V7 同一写法）。
ALTER TABLE ticket_record
    DROP CHECK ck_ticket_record_type,
    ADD CONSTRAINT ck_ticket_record_type CHECK (record_type IN
        ('CREATE', 'CLAIM', 'PROCESS', 'CATEGORY_CHANGE', 'PRIORITY_CHANGE', 'TRANSFER',
         'ADMIN_HANDOFF', 'SUPPLEMENT_REQUEST', 'REQUESTER_SUPPLEMENT', 'SUPPLEMENT_REQUEST_WITHDRAWN',
         'RESOLUTION', 'UNSATISFIED_FEEDBACK', 'COMPLETION', 'CANCELLATION', 'CLOSURE',
         'CANCELLATION_REQUEST', 'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED',
         'CANCELLATION_REQUEST_WITHDRAWN', 'CANCELLATION_REQUEST_EXPIRED'));

-- IT 列表「待我批准」筛选（docs/api-design.md 5.3、完整版 backlog 第 7 项）：
-- 语义是「本人是当前负责人 且 工单上有未过期的待决请求」，判定里带状态白名单
-- （处理中 / 待确认——待补充期间不裁决），因此索引把 status 放在期限之前：
-- 等值前缀（assignee_id + status）之后，cancel_request_deadline_at 才能作为范围条件用上。
-- V7 的「不新增索引」以「待决请求不是查询维度」为前提，本筛选让该前提失效。
--
-- 选型实测（2026-10-12，临时库 10000 行；该负责人 1428 行、有待决请求 714 行、命中 179 行）：
--   不建新索引（走 idx_ticket_assignee_status_updated）：range，rows=714
--   (assignee_id, cancel_request_deadline_at, id)       ：range，rows=358
--   (assignee_id, status, cancel_request_deadline_at, id)：range，rows=179（与结果集等量）
-- 因此采用第三条。它不替代 idx_ticket_assignee_status_updated：那条的第三列是 updated_at，
-- 供 ASSIGNED_TO_ME 的默认排序使用，承担不了期限的范围条件。
ALTER TABLE ticket
    ADD INDEX idx_ticket_assignee_status_cancel_deadline (assignee_id, status, cancel_request_deadline_at, id);
