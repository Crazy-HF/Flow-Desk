package com.flowdesk.ticket.infrastructure.persistence;

import com.flowdesk.ticket.domain.TicketRecord;
import lombok.Getter;
import lombok.Setter;

/** 时间线查询投影：记录字段与操作者展示名称。仅在服务内部使用。 */
@Getter
@Setter
public class TicketRecordRow extends TicketRecord {
    private String actorDisplayName;
}
