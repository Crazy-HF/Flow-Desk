-- FlowDesk demo 数据：仅当激活 demo Profile（spring.flyway.locations 含 classpath:db/demo）时执行。
-- prod 只扫描 classpath:db/migration，绝不会加载本文件。
-- 演示凭据（仅限本地/演示环境，禁止用于任何真实环境）：
--   demo.employee / demo.it / demo.admin 的密码统一为 Demo#FlowDesk2026
-- 密码摘要使用 Argon2id（Spring Security 默认参数 m=16384,t=2,p=1）。
-- 本脚本必须可重复执行：INSERT IGNORE 保证不覆盖真实数据、不重复造数。

-- 1. 演示用户（固定 Argon2id 哈希，三个用户各不相同但均对应同一演示密码）
INSERT IGNORE INTO iam_user (username, display_name, password_hash, status, created_at, updated_at, version) VALUES
    ('demo.employee', '演示员工',
     '$argon2id$v=19$m=16384,t=2,p=1$O4C91k/J1ot360vjHTkPmw$ORD6C7ZfULfZD0gpEgENLN+nQ4w74yHWoTEq+Z3C7OI',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('demo.it', '演示 IT 支持人员',
     '$argon2id$v=19$m=16384,t=2,p=1$PD9hHLWEgjMO8ScbbmZFhw$QCCeXBVHH4/BWsrAtsJTgY0GyQ4b6d2//3IpaSnEQKA',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('demo.admin', '演示系统管理员',
     '$argon2id$v=19$m=16384,t=2,p=1$o3/61AD10TImxTSTDZqZAA$KPO/dqyGikxCcUpLCssJym0Iv26lyQZ+9QdAJ0kIteo',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0);

-- 2. 演示用户角色（系统初始化授权，granted_by 为空）
INSERT IGNORE INTO iam_user_role (user_id, role_id, granted_by, granted_at)
SELECT u.id, r.id, NULL, '2026-01-01 00:00:00.000'
FROM iam_user u
JOIN iam_role r ON r.code = CASE u.username
    WHEN 'demo.employee' THEN 'EMPLOYEE'
    WHEN 'demo.it'       THEN 'IT_SUPPORT'
    WHEN 'demo.admin'    THEN 'SYSTEM_ADMIN'
END
WHERE u.username IN ('demo.employee', 'demo.it', 'demo.admin');

-- 3. 演示分类（含一个停用示例，便于演示启停状态）
INSERT IGNORE INTO ticket_category (name, status, sort_order, created_at, updated_at, version) VALUES
    ('账号与权限', 'ENABLED',   1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('网络访问',   'ENABLED',   2, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('办公设备',   'ENABLED',   3, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('软件安装',   'ENABLED',   4, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('打印与扫描', 'DISABLED',  5, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0);
