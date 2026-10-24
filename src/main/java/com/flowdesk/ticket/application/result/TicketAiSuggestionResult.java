package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;
import java.util.List;

/** 生成或历史查询返回的 AI 建议及其实际输入快照。 */
public record TicketAiSuggestionResult(
        Long id,
        String ticketNo,
        String requestId,
        TicketAiInputSnapshotResult input,
        String summary,
        List<String> questions,
        List<String> suggestedSteps,
        String model,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens,
        OffsetDateTime createdAt) {

    /** 防御性复制问题和步骤列表，避免返回结果在构造后被外部修改。 */
    public TicketAiSuggestionResult {
        questions = List.copyOf(questions);
        suggestedSteps = List.copyOf(suggestedSteps);
    }
}
