-- 单张工单 AI 处理辅助：独立授权、北京时间日额度、请求去重与受限历史。
-- 所有 DATETIME(3) 存 UTC；quota_date 使用 Asia/Shanghai 日历日期，由应用层计算。
-- 不改 ticket_record；模型输出只有在工单状态、负责人、版本复核通过后才进入历史。

INSERT INTO iam_permission (code, name, description, created_at)
VALUES ('TICKET_AI_ASSIST', 'AI 处理辅助', '为当前负责的处理中工单生成 AI 处理建议', '2026-01-01 00:00:00.000');

INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at)
SELECT role.id, permission.id, NULL, NULL
FROM iam_role role
JOIN iam_permission permission ON permission.code = 'TICKET_AI_ASSIST'
WHERE role.code = 'IT_SUPPORT';

-- 每位用户每天一行。占额事务用原子 UPDATE（used_count < 配置上限）串行化并发请求。
-- 通过前置校验且成功占额后，即使供应商失败也计一次；普通校验拒绝不占额。
CREATE TABLE ticket_ai_daily_quota (
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '发起生成的用户',
    quota_date  DATE            NOT NULL COMMENT 'Asia/Shanghai 日历日期',
    used_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '当日已占用调用名额',
    created_at  DATETIME(3)     NOT NULL COMMENT 'UTC 创建时间',
    updated_at  DATETIME(3)     NOT NULL COMMENT 'UTC 最近占额时间',
    PRIMARY KEY (user_id, quota_date),
    CONSTRAINT fk_ticket_ai_daily_quota_user FOREIGN KEY (user_id) REFERENCES iam_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 生成每日调用额度';

-- request_id 由客户端每次确认时生成规范 UUID；同一用户重复提交同一 ID 不再调用供应商。
-- RESERVED 超时或进程中断后只能转 UNKNOWN，不允许同一 request_id 再次发起供应商请求。
-- 请求表只保存元数据，不保存工单正文或模型输出。
CREATE TABLE ticket_ai_request (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id       BIGINT UNSIGNED NOT NULL COMMENT '目标工单',
    user_id         BIGINT UNSIGNED NOT NULL COMMENT '发起用户',
    request_id      CHAR(36)        NOT NULL COMMENT '客户端规范 UUID，用户范围内去重',
    ticket_version  BIGINT UNSIGNED NOT NULL COMMENT '调用前确认的工单版本',
    input_sha256    CHAR(64)        NOT NULL COMMENT '五项规范化输入的 SHA-256；重复请求必须与原请求一致',
    status          VARCHAR(16)     NOT NULL COMMENT 'RESERVED/SUCCEEDED/FAILED/STALE/UNKNOWN',
    failure_code    VARCHAR(64)     NULL COMMENT '失败或结果不确定时的稳定业务码，不含正文',
    created_at      DATETIME(3)     NOT NULL COMMENT 'UTC 占额时间',
    finished_at     DATETIME(3)     NULL COMMENT 'UTC 结束时间；RESERVED 时为空',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_ai_request_user_request UNIQUE (user_id, request_id),
    CONSTRAINT uk_ticket_ai_request_id_ticket UNIQUE (id, ticket_id),
    CONSTRAINT fk_ticket_ai_request_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_ai_request_user FOREIGN KEY (user_id) REFERENCES iam_user (id),
    CONSTRAINT ck_ticket_ai_request_status CHECK (
        status IN ('RESERVED', 'SUCCEEDED', 'FAILED', 'STALE', 'UNKNOWN')),
    CONSTRAINT ck_ticket_ai_request_finished CHECK (
        (status = 'RESERVED' AND finished_at IS NULL)
        OR (status <> 'RESERVED' AND finished_at IS NOT NULL)),
    INDEX idx_ticket_ai_request_ticket_created (ticket_id, created_at, id),
    INDEX idx_ticket_ai_request_status_created (status, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 生成请求与幂等元数据';

-- 仅成功生成且复核通过才保存一行。历史读取还需实时检查工单可见性与当前/最后负责人。
-- 清理以 ticket.ended_at 为准：未结束工单保留；结束满 30 天删除建议及对应请求元数据。
CREATE TABLE ticket_ai_suggestion (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_row_id        BIGINT UNSIGNED NOT NULL COMMENT '对应的唯一生成请求',
    ticket_id             BIGINT UNSIGNED NOT NULL COMMENT '所属工单',
    input_title           VARCHAR(200)    NOT NULL COMMENT '确认后实际发送的标题',
    input_description     TEXT            NOT NULL COMMENT '确认后实际发送的问题描述',
    input_category        VARCHAR(100)    NOT NULL COMMENT '发送时的分类名称快照',
    input_priority        VARCHAR(16)     NOT NULL COMMENT '发送时的优先级编码',
    input_status          VARCHAR(32)     NOT NULL COMMENT '发送时的工单状态编码',
    summary               VARCHAR(2000)   NOT NULL COMMENT 'AI 摘要',
    questions_json        JSON            NOT NULL COMMENT '待核实问题字符串数组',
    suggested_steps_json  JSON            NOT NULL COMMENT '建议步骤字符串数组',
    model                 VARCHAR(64)     NOT NULL COMMENT '实际使用的模型名',
    prompt_version        VARCHAR(32)     NOT NULL COMMENT '应用提示词模板版本',
    input_tokens          INT UNSIGNED    NULL COMMENT '供应商返回的输入 token 数',
    output_tokens         INT UNSIGNED    NULL COMMENT '供应商返回的输出 token 数',
    created_at            DATETIME(3)     NOT NULL COMMENT 'UTC 保存时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_ai_suggestion_request UNIQUE (request_row_id),
    CONSTRAINT fk_ticket_ai_suggestion_request_ticket
        FOREIGN KEY (request_row_id, ticket_id) REFERENCES ticket_ai_request (id, ticket_id),
    CONSTRAINT fk_ticket_ai_suggestion_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT ck_ticket_ai_suggestion_priority CHECK (input_priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_ticket_ai_suggestion_status CHECK (input_status = 'PROCESSING'),
    INDEX idx_ticket_ai_suggestion_ticket_created (ticket_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '受限访问的 AI 工单建议历史';

-- 终态清理从 ended_at 定位工单，再分批删除建议与请求；不修改已发布迁移。
CREATE INDEX idx_ticket_ended_at_id ON ticket (ended_at, id);
