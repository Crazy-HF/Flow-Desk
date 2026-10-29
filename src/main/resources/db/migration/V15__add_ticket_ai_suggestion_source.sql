-- B-L1 来源快照。旧 V14 请求的 source_digest 保持 NULL，继续使用原五项输入指纹。
ALTER TABLE ticket_ai_request
    ADD COLUMN source_digest CHAR(64) NULL COMMENT '生成确认时的来源摘要；NULL 表示旧 v4 请求';

-- 只保存实际发送给模型且最终成功的来源。删除建议时级联清理快照。
CREATE TABLE ticket_ai_suggestion_source (
    suggestion_id   BIGINT UNSIGNED NOT NULL,
    sequence_no     TINYINT UNSIGNED NOT NULL COMMENT '1 至 5，对应 S 标签',
    source_ticket_id BIGINT UNSIGNED NOT NULL COMMENT '来源工单，用于实时权限复核',
    ticket_no       VARCHAR(32) NOT NULL COMMENT '生成时的来源编号快照，仅返回给有权用户',
    title           VARCHAR(200) NOT NULL,
    process_excerpt TEXT NOT NULL,
    PRIMARY KEY (suggestion_id, sequence_no),
    CONSTRAINT fk_ticket_ai_source_suggestion FOREIGN KEY (suggestion_id)
        REFERENCES ticket_ai_suggestion (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_ai_source_ticket FOREIGN KEY (source_ticket_id)
        REFERENCES ticket (id),
    CONSTRAINT ck_ticket_ai_source_sequence CHECK (sequence_no BETWEEN 1 AND 5),
    INDEX idx_ticket_ai_source_ticket (source_ticket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 建议实际外发的来源快照';
