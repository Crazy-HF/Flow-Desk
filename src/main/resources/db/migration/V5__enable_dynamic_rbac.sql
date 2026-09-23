-- 动态 RBAC：补充角色权限授权审计字段，并开放 RBAC 管理权限。
-- 历史迁移与本迁移产生的种子授权允许审计字段为空；在线授权由应用层保证审计字段必填。

ALTER TABLE iam_role_permission
    ADD COLUMN granted_by BIGINT UNSIGNED NULL COMMENT '授权人用户 ID' AFTER permission_id,
    ADD COLUMN granted_at DATETIME(6) NULL COMMENT '授权时间' AFTER granted_by,
    ADD INDEX idx_iam_role_permission_granted_by (granted_by),
    ADD CONSTRAINT fk_iam_role_permission_granted_by
        FOREIGN KEY (granted_by) REFERENCES iam_user (id);

INSERT INTO iam_permission (code, name, description, created_at)
VALUES ('RBAC_MANAGE', 'RBAC 管理', '管理角色、权限及授权关系', '2026-01-01 00:00:00.000');

INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at)
SELECT role.id, permission.id, NULL, NULL
FROM iam_role role
JOIN iam_permission permission ON permission.code = 'RBAC_MANAGE'
WHERE role.code = 'SYSTEM_ADMIN';
