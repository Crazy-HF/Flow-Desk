-- 工单编号按北京时间业务日期独立递增，不依赖 ticket.id。
CREATE TABLE ticket_daily_sequence (
    business_date DATE NOT NULL COMMENT 'Asia/Shanghai 业务日期',
    current_value BIGINT NOT NULL COMMENT '当天已分配的最大序号',
    PRIMARY KEY (business_date),
    CONSTRAINT ck_ticket_daily_sequence_positive CHECK (current_value > 0)
) ENGINE = InnoDB COMMENT = '工单每日编号序号';

-- 兼容已经存在的日期编号，避免升级后从 001 开始造成重复。
INSERT INTO ticket_daily_sequence (business_date, current_value)
SELECT STR_TO_DATE(SUBSTRING(ticket_no, 4, 8), '%Y%m%d'),
       MAX(CAST(SUBSTRING(ticket_no, 13) AS SIGNED))
FROM ticket
WHERE ticket_no REGEXP '^FD-[0-9]{8}-[0-9]{3,}$'
GROUP BY STR_TO_DATE(SUBSTRING(ticket_no, 4, 8), '%Y%m%d');
