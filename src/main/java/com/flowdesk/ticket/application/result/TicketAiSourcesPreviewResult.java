package com.flowdesk.ticket.application.result;

import java.util.List;

/** 负责人确认前看到的实际来源摘录；内部工单 ID 和编号不进入预览响应。 */
public record TicketAiSourcesPreviewResult(
        List<Source> sources,
        String sourceDigest) {

    public TicketAiSourcesPreviewResult {
        sources = List.copyOf(sources);
    }

    /** 标签由服务端按本次召回顺序分配，不能由客户端指定。 */
    public record Source(
            String label,
            String title,
            String processExcerpt) {
    }
}
