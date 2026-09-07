-- FlowDesk 预置 RBAC 基线：3 个角色、13 项权限及其映射。
-- 编码与 docs/database-design.md 第 21 节及 docs/api-design.md 权限映射一致。
-- 本迁移不创建任何用户，也不包含默认管理员密码；生产首个管理员的引导方式由生产部署任务单独实现。
-- 固定时间戳避免依赖系统当前时间；角色/权限/映射 v1 由版本迁移维护，不提供运行时编辑入口。

INSERT INTO iam_role (code, name, description, created_at) VALUES
    ('EMPLOYEE',     '普通员工',     '提交、查看和确认自己的工单', '2026-01-01 00:00:00.000'),
    ('IT_SUPPORT',   'IT 支持人员', '受理、处理、转交和关闭工单', '2026-01-01 00:00:00.000'),
    ('SYSTEM_ADMIN', '系统管理员',   '用户管理、分类管理和管理性交接，不包含工单正文访问', '2026-01-01 00:00:00.000');

INSERT INTO iam_permission (code, name, description, created_at) VALUES
    ('TICKET_CREATE',             '创建工单',             '创建工单并使用提交幂等键', '2026-01-01 00:00:00.000'),
    ('TICKET_VIEW_OWN',           '查看自己的工单',       '按提交人关系查看工单', '2026-01-01 00:00:00.000'),
    ('TICKET_REQUESTER_ACTION',   '提交人操作',           '补充、确认、反馈未解决或撤销自己的工单', '2026-01-01 00:00:00.000'),
    ('TICKET_VIEW_QUEUE',         '查看待受理队列',       '查看公共待受理工单队列', '2026-01-01 00:00:00.000'),
    ('TICKET_CLAIM',              '领取工单',             '领取符合条件的待受理工单', '2026-01-01 00:00:00.000'),
    ('TICKET_VIEW_PARTICIPATED',  '查看参与工单',         '查看当前或历史参与的工单', '2026-01-01 00:00:00.000'),
    ('TICKET_PROCESS',            '处理工单',             '执行当前负责人的处理操作', '2026-01-01 00:00:00.000'),
    ('TICKET_TRANSFER',           '转交工单',             '转交当前负责的工单', '2026-01-01 00:00:00.000'),
    ('TICKET_CLOSE',              '关闭工单',             '异常关闭当前负责的工单', '2026-01-01 00:00:00.000'),
    ('TICKET_ADMIN_HANDOFF',      '管理性交接',           '读取最小元数据并执行管理性交接，不授予工单正文访问', '2026-01-01 00:00:00.000'),
    ('USER_MANAGE',               '用户管理',             '管理用户状态和用户角色', '2026-01-01 00:00:00.000'),
    ('CATEGORY_MANAGE',           '分类管理',             '管理工作单分类', '2026-01-01 00:00:00.000'),
    ('DASHBOARD_VIEW',            '数据概览',             '查看权限范围内的数据概览', '2026-01-01 00:00:00.000');

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    -- EMPLOYEE
    'TICKET_CREATE', 'TICKET_VIEW_OWN', 'TICKET_REQUESTER_ACTION')
WHERE r.code = 'EMPLOYEE';

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    -- IT_SUPPORT
    'TICKET_VIEW_QUEUE', 'TICKET_CLAIM', 'TICKET_VIEW_PARTICIPATED',
    'TICKET_PROCESS', 'TICKET_TRANSFER', 'TICKET_CLOSE', 'DASHBOARD_VIEW')
WHERE r.code = 'IT_SUPPORT';

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    -- SYSTEM_ADMIN：不包含查看具体工单内容的权限
    'TICKET_ADMIN_HANDOFF', 'USER_MANAGE', 'CATEGORY_MANAGE')
WHERE r.code = 'SYSTEM_ADMIN';
