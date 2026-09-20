-- 统一密码列命名为 password，与实体字段和认证读模型保持一致。
-- V1 已发布不能修改，列重命名只能通过新迁移表达。
-- 采用条件语句而非直接 RENAME：已有库可能被手工调整过列名，重复执行不能失败。
SET @needs_rename := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'iam_user' AND column_name = 'password_hash'
);
SET @rename_sql := IF(@needs_rename > 0,
    'ALTER TABLE iam_user RENAME COLUMN password_hash TO password',
    'DO 0');
PREPARE rename_password FROM @rename_sql;
EXECUTE rename_password;
DEALLOCATE PREPARE rename_password;
