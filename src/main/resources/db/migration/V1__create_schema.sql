-- FlowDesk 数据基线：11 张表，对应 docs/database-design.md 已确认的 MySQL 物理模型。
-- 约定：InnoDB / utf8mb4 / utf8mb4_0900_ai_ci / DATETIME(3) 统一保存 UTC / 固定枚举使用字符串编码 + CHECK。
-- 所有业务外键均为限制删除（RESTRICT，未声明 ON DELETE 即默认行为），禁止级联清理历史。

-- ============================================================
-- 身份与访问
-- ============================================================

CREATE TABLE iam_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)     NOT NULL COMMENT '登录名，不区分大小写唯一',
    display_name  VARCHAR(100)    NOT NULL COMMENT '页面展示名称',
    password_hash VARCHAR(255)    NOT NULL COMMENT '密码安全摘要（Argon2id），不保存明文',
    status        VARCHAR(16)     NOT NULL COMMENT '账号状态',
    created_at    DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    updated_at    DATETIME(3)     NOT NULL COMMENT 'UTC 最近更新时间',
    version       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '账号与角色管理的乐观版本',
    PRIMARY KEY (id),
    CONSTRAINT uk_iam_user_username UNIQUE (username),
    CONSTRAINT ck_iam_user_status CHECK (status IN ('ENABLED', 'DISABLED')),
    INDEX idx_iam_user_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户';

CREATE TABLE iam_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)     NOT NULL COMMENT '稳定角色编码',
    name        VARCHAR(50)     NOT NULL COMMENT '展示名称',
    description VARCHAR(255)    NULL COMMENT '角色说明',
    created_at  DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_iam_role_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预置角色';

CREATE TABLE iam_permission (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(100)    NOT NULL COMMENT '稳定业务权限编码，不使用 URL 或前端标识',
    name        VARCHAR(100)    NOT NULL COMMENT '展示名称',
    description VARCHAR(255)    NULL COMMENT '权限说明',
    created_at  DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_iam_permission_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预置权限';

CREATE TABLE iam_user_role (
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户',
    role_id    BIGINT UNSIGNED NOT NULL COMMENT '角色',
    granted_by BIGINT UNSIGNED NULL COMMENT '授权管理员；系统初始化时为空',
    granted_at DATETIME(3)     NOT NULL COMMENT 'UTC 授权时间',
    PRIMARY KEY (user_id, role_id),
    INDEX idx_iam_user_role_role_user (role_id, user_id),
    CONSTRAINT fk_iam_user_role_user FOREIGN KEY (user_id) REFERENCES iam_user (id),
    CONSTRAINT fk_iam_user_role_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_iam_user_role_granted_by FOREIGN KEY (granted_by) REFERENCES iam_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户与角色多对多关系';

CREATE TABLE iam_role_permission (
    role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色',
    permission_id BIGINT UNSIGNED NOT NULL COMMENT '权限',
    PRIMARY KEY (role_id, permission_id),
    INDEX idx_iam_role_permission_permission_role (permission_id, role_id),
    CONSTRAINT fk_iam_role_permission_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_iam_role_permission_permission FOREIGN KEY (permission_id) REFERENCES iam_permission (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色与权限多对多关系';

-- ============================================================
-- 分类
-- ============================================================

CREATE TABLE ticket_category (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100)    NOT NULL COMMENT '分类名称，全局唯一',
    status     VARCHAR(16)     NOT NULL COMMENT '分类状态',
    sort_order INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '展示顺序',
    created_at DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    updated_at DATETIME(3)     NOT NULL COMMENT 'UTC 最近更新时间',
    version    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观版本',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_category_name UNIQUE (name),
    CONSTRAINT ck_ticket_category_status CHECK (status IN ('ENABLED', 'DISABLED')),
    INDEX idx_ticket_category_status_sort (status, sort_order, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '单级工单分类';

-- ============================================================
-- 工单核心
-- ============================================================

CREATE TABLE ticket (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_no          VARCHAR(32)     NOT NULL COMMENT '对外业务编号，如 FD-20260907-000001',
    submission_key     CHAR(36)        NOT NULL COMMENT '提交端 UUID，与提交人组合保证创建幂等',
    requester_id       BIGINT UNSIGNED NOT NULL COMMENT '提交人',
    title              VARCHAR(200)    NOT NULL COMMENT '原始标题，提交后不可覆盖',
    description        TEXT            NOT NULL COMMENT '原始问题描述，提交后不可覆盖',
    category_id        BIGINT UNSIGNED NOT NULL COMMENT '当前分类',
    priority           VARCHAR(16)     NOT NULL COMMENT '优先级',
    status             VARCHAR(32)     NOT NULL COMMENT '当前状态',
    assignee_id        BIGINT UNSIGNED NULL COMMENT '当前负责人；终态时保留最后负责人',
    action_deadline_at DATETIME(3)     NULL COMMENT '待补充或待确认的当前有效截止时间',
    completion_method  VARCHAR(32)     NULL COMMENT '完成方式，仅已完成使用',
    close_method       VARCHAR(16)     NULL COMMENT '关闭方式，仅已关闭使用',
    close_reason       VARCHAR(32)     NULL COMMENT '关闭标准原因，仅已关闭使用',
    ended_at           DATETIME(3)     NULL COMMENT '进入终态的 UTC 时间',
    record_seq         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '当前最大工单内记录序号',
    version            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '工单乐观版本',
    created_at         DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    updated_at         DATETIME(3)     NOT NULL COMMENT 'UTC 最近更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_no UNIQUE (ticket_no),
    CONSTRAINT uk_ticket_requester_submission UNIQUE (requester_id, submission_key),
    INDEX idx_ticket_requester_created (requester_id, created_at, id),
    INDEX idx_ticket_status_priority_created (status, priority, created_at, id),
    INDEX idx_ticket_assignee_status_updated (assignee_id, status, updated_at, id),
    INDEX idx_ticket_status_deadline (status, action_deadline_at, id),
    INDEX idx_ticket_category_status (category_id, status),
    CONSTRAINT fk_ticket_requester FOREIGN KEY (requester_id) REFERENCES iam_user (id),
    CONSTRAINT fk_ticket_assignee FOREIGN KEY (assignee_id) REFERENCES iam_user (id),
    CONSTRAINT fk_ticket_category FOREIGN KEY (category_id) REFERENCES ticket_category (id),
    CONSTRAINT ck_ticket_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_ticket_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION',
         'COMPLETED', 'CANCELED', 'CLOSED')),
    -- 待受理无负责人；处理中/待补充/待确认/已完成/已关闭必须有负责人；已取消可为空
    CONSTRAINT ck_ticket_status_assignee CHECK (
        (status = 'PENDING' AND assignee_id IS NULL)
        OR (status IN ('PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION', 'COMPLETED', 'CLOSED')
            AND assignee_id IS NOT NULL)
        OR (status = 'CANCELED')),
    -- 负责人不能是工单提交人
    CONSTRAINT ck_ticket_assignee_not_requester CHECK (
        assignee_id IS NULL OR assignee_id <> requester_id),
    -- 只有待补充和待确认有有效截止时间
    CONSTRAINT ck_ticket_status_deadline CHECK (
        (status IN ('WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION') AND action_deadline_at IS NOT NULL)
        OR (status NOT IN ('WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION')
            AND action_deadline_at IS NULL)),
    -- 非终态无结束时间，终态必须有结束时间
    CONSTRAINT ck_ticket_status_ended CHECK (
        (status IN ('COMPLETED', 'CANCELED', 'CLOSED') AND ended_at IS NOT NULL)
        OR (status NOT IN ('COMPLETED', 'CANCELED', 'CLOSED') AND ended_at IS NULL)),
    -- 仅已完成允许且要求完成方式
    CONSTRAINT ck_ticket_status_completion_method CHECK (
        (status = 'COMPLETED' AND completion_method IS NOT NULL)
        OR (status <> 'COMPLETED' AND completion_method IS NULL)),
    -- 仅已关闭允许且要求关闭方式和关闭原因
    CONSTRAINT ck_ticket_status_close_fields CHECK (
        (status = 'CLOSED' AND close_method IS NOT NULL AND close_reason IS NOT NULL)
        OR (status <> 'CLOSED' AND close_method IS NULL AND close_reason IS NULL)),
    -- 系统自动关闭只能使用逾期未补充；手动关闭只能使用重复/超范围/无效
    CONSTRAINT ck_ticket_close_semantics CHECK (
        (close_method IS NULL AND close_reason IS NULL)
        OR (close_method = 'AUTO_SUPPLEMENT_TIMEOUT' AND close_reason = 'REQUESTER_NO_RESPONSE')
        OR (close_method = 'MANUAL' AND close_reason IN ('DUPLICATE', 'OUT_OF_SCOPE', 'INVALID'))),
    CONSTRAINT ck_ticket_completion_method CHECK (
        completion_method IS NULL OR completion_method IN ('REQUESTER_CONFIRMED', 'AUTO_CONFIRM_TIMEOUT')),
    CONSTRAINT ck_ticket_close_method CHECK (
        close_method IS NULL OR close_method IN ('MANUAL', 'AUTO_SUPPLEMENT_TIMEOUT')),
    CONSTRAINT ck_ticket_close_reason CHECK (
        close_reason IS NULL OR close_reason IN ('DUPLICATE', 'OUT_OF_SCOPE', 'INVALID', 'REQUESTER_NO_RESPONSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单当前快照';

CREATE TABLE ticket_record (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id         BIGINT UNSIGNED NOT NULL COMMENT '所属工单',
    sequence_no       INT UNSIGNED    NOT NULL COMMENT '工单内严格递增序号',
    record_type       VARCHAR(32)     NOT NULL COMMENT '业务记录类型',
    actor_type        VARCHAR(16)     NOT NULL COMMENT '执行者类型：USER 或 SYSTEM',
    actor_user_id     BIGINT UNSIGNED NULL COMMENT '用户操作时指向执行用户；系统操作为空',
    content           TEXT            NULL COMMENT '处理正文、补充正文或解决结论',
    reason            VARCHAR(1000)   NULL COMMENT '转交、调整、撤回、未解决、取消或关闭说明',
    from_status       VARCHAR(32)     NULL COMMENT '原状态快照',
    to_status         VARCHAR(32)     NULL COMMENT '新状态快照',
    from_assignee_id  BIGINT UNSIGNED NULL COMMENT '原负责人快照',
    to_assignee_id    BIGINT UNSIGNED NULL COMMENT '新负责人快照',
    from_category_id  BIGINT UNSIGNED NULL COMMENT '原分类快照',
    to_category_id    BIGINT UNSIGNED NULL COMMENT '新分类快照',
    from_priority     VARCHAR(16)     NULL COMMENT '原优先级编码',
    to_priority       VARCHAR(16)     NULL COMMENT '新优先级编码',
    deadline_at       DATETIME(3)     NULL COMMENT '补充请求或解决结果产生的截止时间',
    completion_method VARCHAR(32)     NULL COMMENT '完成记录使用',
    close_method      VARCHAR(16)     NULL COMMENT '关闭记录使用',
    close_reason      VARCHAR(32)     NULL COMMENT '关闭记录使用',
    created_at        DATETIME(3)     NOT NULL COMMENT '业务动作发生的 UTC 时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_record_ticket_seq UNIQUE (ticket_id, sequence_no),
    INDEX idx_ticket_record_actor_created (actor_user_id, created_at, id),
    CONSTRAINT fk_ticket_record_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_record_actor_user FOREIGN KEY (actor_user_id) REFERENCES iam_user (id),
    CONSTRAINT fk_ticket_record_from_assignee FOREIGN KEY (from_assignee_id) REFERENCES iam_user (id),
    CONSTRAINT fk_ticket_record_to_assignee FOREIGN KEY (to_assignee_id) REFERENCES iam_user (id),
    CONSTRAINT fk_ticket_record_from_category FOREIGN KEY (from_category_id) REFERENCES ticket_category (id),
    CONSTRAINT fk_ticket_record_to_category FOREIGN KEY (to_category_id) REFERENCES ticket_category (id),
    CONSTRAINT ck_ticket_record_type CHECK (record_type IN
        ('CREATE', 'CLAIM', 'PROCESS', 'CATEGORY_CHANGE', 'PRIORITY_CHANGE', 'TRANSFER',
         'ADMIN_HANDOFF', 'SUPPLEMENT_REQUEST', 'REQUESTER_SUPPLEMENT', 'SUPPLEMENT_REQUEST_WITHDRAWN',
         'RESOLUTION', 'UNSATISFIED_FEEDBACK', 'COMPLETION', 'CANCELLATION', 'CLOSURE')),
    CONSTRAINT ck_ticket_record_actor_pair CHECK (
        (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
        OR (actor_type = 'USER' AND actor_user_id IS NOT NULL)),
    CONSTRAINT ck_ticket_record_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_ticket_record_from_status CHECK (
        from_status IS NULL OR from_status IN
        ('PENDING', 'PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION',
         'COMPLETED', 'CANCELED', 'CLOSED')),
    CONSTRAINT ck_ticket_record_to_status CHECK (
        to_status IS NULL OR to_status IN
        ('PENDING', 'PROCESSING', 'WAITING_FOR_REQUESTER', 'WAITING_FOR_CONFIRMATION',
         'COMPLETED', 'CANCELED', 'CLOSED')),
    CONSTRAINT ck_ticket_record_from_priority CHECK (
        from_priority IS NULL OR from_priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_ticket_record_to_priority CHECK (
        to_priority IS NULL OR to_priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_ticket_record_completion_method CHECK (
        completion_method IS NULL OR completion_method IN ('REQUESTER_CONFIRMED', 'AUTO_CONFIRM_TIMEOUT')),
    CONSTRAINT ck_ticket_record_close_method CHECK (
        close_method IS NULL OR close_method IN ('MANUAL', 'AUTO_SUPPLEMENT_TIMEOUT')),
    CONSTRAINT ck_ticket_record_close_reason CHECK (
        close_reason IS NULL OR close_reason IN ('DUPLICATE', 'OUT_OF_SCOPE', 'INVALID', 'REQUESTER_NO_RESPONSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单不可变记录时间线';

CREATE TABLE ticket_attachment (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    record_id     BIGINT UNSIGNED NOT NULL COMMENT '所属工单记录',
    storage_key   VARCHAR(255)    NOT NULL COMMENT '系统生成的存储标识',
    original_name VARCHAR(255)    NOT NULL COMMENT '仅用于展示的原始文件名',
    content_type  VARCHAR(127)    NOT NULL COMMENT '服务端识别后的媒体类型',
    size_bytes    BIGINT UNSIGNED NOT NULL COMMENT '文件字节数，必须大于 0',
    sha256        CHAR(64)        NOT NULL COMMENT '文件完整性摘要',
    uploaded_by   BIGINT UNSIGNED NOT NULL COMMENT '上传用户',
    created_at    DATETIME(3)     NOT NULL COMMENT 'UTC 上传时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_attachment_storage_key UNIQUE (storage_key),
    INDEX idx_ticket_attachment_record (record_id, id),
    CONSTRAINT fk_ticket_attachment_record FOREIGN KEY (record_id) REFERENCES ticket_record (id),
    CONSTRAINT fk_ticket_attachment_uploader FOREIGN KEY (uploaded_by) REFERENCES iam_user (id),
    CONSTRAINT ck_ticket_attachment_size CHECK (size_bytes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单附件元数据';

CREATE TABLE ticket_relation (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_ticket_id BIGINT UNSIGNED NOT NULL COMMENT '来源工单',
    target_ticket_id BIGINT UNSIGNED NOT NULL COMMENT '目标工单',
    relation_type    VARCHAR(32)     NOT NULL COMMENT '关联类型：FOLLOW_UP 或 DUPLICATE',
    created_by       BIGINT UNSIGNED NOT NULL COMMENT '创建关系的用户',
    created_at       DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_relation_source_type UNIQUE (source_ticket_id, relation_type),
    INDEX idx_ticket_relation_target_type_source (target_ticket_id, relation_type, source_ticket_id),
    CONSTRAINT fk_ticket_relation_source FOREIGN KEY (source_ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_relation_target FOREIGN KEY (target_ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_relation_created_by FOREIGN KEY (created_by) REFERENCES iam_user (id),
    CONSTRAINT ck_ticket_relation_type CHECK (relation_type IN ('FOLLOW_UP', 'DUPLICATE')),
    CONSTRAINT ck_ticket_relation_not_self CHECK (source_ticket_id <> target_ticket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单有向关联';

CREATE TABLE ticket_participant (
    ticket_id         BIGINT UNSIGNED NOT NULL COMMENT '工单',
    user_id           BIGINT UNSIGNED NOT NULL COMMENT '曾经成为负责人的 IT 用户',
    first_assigned_at DATETIME(3)     NOT NULL COMMENT '首次成为负责人的 UTC 时间',
    last_assigned_at  DATETIME(3)     NOT NULL COMMENT '最近一次成为负责人的 UTC 时间',
    PRIMARY KEY (ticket_id, user_id),
    INDEX idx_ticket_participant_user_ticket (user_id, ticket_id),
    CONSTRAINT fk_ticket_participant_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_participant_user FOREIGN KEY (user_id) REFERENCES iam_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单历史参与者';
