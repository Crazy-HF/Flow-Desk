-- 缺陷修复：close_method 列宽不足以容纳 CHECK 约束里已登记的取值（完整版 backlog 3「待补充超时自动关闭」）。
--
-- 问题（2026-10-16 首次实跑 TicketTimeoutServiceIT 时发现）：
--   V1 把 ticket.close_method 与 ticket_record.close_method 定义为 VARCHAR(16)，
--   但同一个 V1 的 ck_ticket_close_method / ck_ticket_record_close_method 白名单里
--   写着 'AUTO_SUPPLEMENT_TIMEOUT'——它是 23 个字符。约束与列宽自相矛盾。
--   后果：completeAutoSupplementTimeout 在真库上必然抛
--   "Data truncation: Data too long for column 'close_method'"，
--   而它被 TicketServiceImpl 的单张 try/catch 静默吞掉，表现为 closeExpiredSupplements
--   恒返回 0——「待补充超时自动关闭」整条路径 100% 不可用。
--   此前没有任何测试跑过这条路径（本批补的即为首次覆盖），因此一直未被发现。
--
-- 为什么自动完成没受影响：completion_method 在 V1 就是 VARCHAR(32)，
--   而 AUTO_CONFIRM_TIMEOUT 只有 20 个字符。**加宽只针对 close_method 两处。**
--
-- 取值与约束名都不改：ck_ticket_close_method / ck_ticket_record_close_method 的
--   白名单（'MANUAL' / 'AUTO_SUPPLEMENT_TIMEOUT'）本来就是对的，错的只是列宽。
-- 加宽到 32 与 completion_method 对齐，行为无变化（MySQL VARCHAR 变长存储，
--   加宽不重写数据、不影响既有行）。
-- 表集合、外键总数（25）与索引集合均不变，DatabaseMigrationIT 的基线断言不受影响
--   （只多一条迁移版本号，该断言随本批一并更新为 1..13）。
ALTER TABLE ticket
    MODIFY COLUMN close_method VARCHAR(32) NULL COMMENT '关闭方式，仅已关闭使用';

ALTER TABLE ticket_record
    MODIFY COLUMN close_method VARCHAR(32) NULL COMMENT '关闭记录使用';
