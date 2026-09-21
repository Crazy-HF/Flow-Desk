-- FlowDesk demo 数据：仅当激活 demo Profile（spring.flyway.locations 含 classpath:db/demo）时执行。
-- prod 只扫描 classpath:db/migration，绝不会加载本文件。
-- 演示凭据（仅限本地/演示环境，禁止用于任何真实环境）：
--   employee / it / admin 的密码统一为 123456
-- 密码摘要使用 Argon2id，参数与 common/config/PasswordConfiguration 一致（salt 16、hash 32、m=19456,t=2,p=1）。
-- 本脚本必须可重复执行：INSERT IGNORE 保证不覆盖真实数据、不重复造数。

-- 1. 演示用户（固定 Argon2id 哈希，三个用户各不相同但均对应同一演示密码）
INSERT IGNORE INTO iam_user (username, display_name, password, status, created_at, updated_at, version) VALUES
    ('employee', '演示员工',
     '$argon2id$v=19$m=19456,t=2,p=1$r6VCp8Q1q4i/lwJgq2A3xQ$XJYZhHxsgKcfsOZicF/GGGcB6A6Y90HAxwL+Ng4WU8c',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('it', '演示 IT 支持人员',
     '$argon2id$v=19$m=19456,t=2,p=1$7WDkNKxoDVrx18OYyEDLLg$qpFphPnqkMh2JiPs/bMfoiww8LD59tcwtAnadUHfOdw',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('admin', '演示系统管理员',
     '$argon2id$v=19$m=19456,t=2,p=1$MTAso4nK5FW/lG6RQinEFw$Lu2aFJwsaJAmu9LbgU+yLHTT+pLBe0kyJJxQpe6Bjto',
     'ENABLED', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0);

-- 2. 演示用户角色（系统初始化授权，granted_by 为空）
INSERT IGNORE INTO iam_user_role (user_id, role_id, granted_by, granted_at)
SELECT u.id, r.id, NULL, '2026-01-01 00:00:00.000'
FROM iam_user u
JOIN iam_role r ON r.code = CASE u.username
    WHEN 'employee' THEN 'EMPLOYEE'
    WHEN 'it'       THEN 'IT_SUPPORT'
    WHEN 'admin'    THEN 'SYSTEM_ADMIN'
END
WHERE u.username IN ('employee', 'it', 'admin');

-- 3. 演示分类（含一个停用示例，便于演示启停状态）
INSERT IGNORE INTO ticket_category (name, status, sort_order, created_at, updated_at, version) VALUES
    ('账号与权限', 'ENABLED',   1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('网络访问',   'ENABLED',   2, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('办公设备',   'ENABLED',   3, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('软件安装',   'ENABLED',   4, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0),
    ('打印与扫描', 'DISABLED',  5, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000', 0);
