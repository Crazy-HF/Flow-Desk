-- V9：附件模块独立化（2026-10-12 用户确认，方案 B）
-- 改号说明（2026-10-13 用户指示）：本迁移原编号 V8；main 的「撤销到期语义」一侧已占用
-- V8（V8__add_cancel_request_expiry.sql），两个 V8 共存会让 Flyway 以
-- "Found more than one migration with version 8" 启动失败，因此附件这一侧改为 V9。
-- 目标形态：通用 attachment 表（业务无关的文件元数据，通用附件模块的权威表）
--          + ticket_attachment 绑定表（工单侧的归属关系，权限仍从工单继承）。
-- V1 建的 ticket_attachment 从未投入使用（附件功能此前未实现、任何环境均无数据），
-- 因此这里直接重建；如未来某环境存在旧结构数据，需单独编写数据迁移，本迁移假定为空表。

DROP TABLE ticket_attachment;

CREATE TABLE attachment (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    storage_key   VARCHAR(255)    NOT NULL COMMENT '系统生成的存储标识',
    original_name VARCHAR(255)    NOT NULL COMMENT '仅用于展示的原始文件名',
    content_type  VARCHAR(127)    NOT NULL COMMENT '服务端识别后的媒体类型',
    size_bytes    BIGINT UNSIGNED NOT NULL COMMENT '文件字节数，必须大于 0',
    sha256        CHAR(64)        NOT NULL COMMENT '文件完整性摘要',
    uploaded_by   BIGINT UNSIGNED NOT NULL COMMENT '上传用户',
    created_at    DATETIME(3)     NOT NULL COMMENT 'UTC 上传时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_attachment_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploaded_by) REFERENCES iam_user (id),
    CONSTRAINT ck_attachment_size CHECK (size_bytes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通用附件元数据（文件内容在本地受控目录）';

CREATE TABLE ticket_attachment (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attachment_id BIGINT UNSIGNED NOT NULL COMMENT '绑定的通用附件',
    record_id     BIGINT UNSIGNED NOT NULL COMMENT '所属工单记录',
    created_at    DATETIME(3)     NOT NULL COMMENT 'UTC 绑定时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_attachment_attachment UNIQUE (attachment_id),
    INDEX idx_ticket_attachment_record (record_id, id),
    CONSTRAINT fk_ticket_attachment_attachment FOREIGN KEY (attachment_id) REFERENCES attachment (id),
    CONSTRAINT fk_ticket_attachment_record FOREIGN KEY (record_id) REFERENCES ticket_record (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单附件绑定：通用附件与工单记录的一对一归属';