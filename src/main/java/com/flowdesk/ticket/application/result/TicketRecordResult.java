package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;
import java.util.Map;

/** context 由服务按记录类型明确选择字段，禁止直接序列化记录实体。 */
public record TicketRecordResult(
        Integer sequenceNo,
        String recordType,
        String actorType,
        TicketUserSummaryResult actor,
        OffsetDateTime createdAt,
        Map<String, Object> context) {
    public TicketRecordResult {
        context = Map.copyOf(context);
    }
}
