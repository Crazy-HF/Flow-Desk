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
        OffsetDateTime createdAt,
        List<Source> sources) {

    /** 来源编号只在当前用户仍能查看该来源时由服务层填入。 */
    public record Source(String label, String ticketNo,
                         String title, String processExcerpt) {
    }

    /** 兼容旧 v4 建议的构造位置。 */
    public TicketAiSuggestionResult(
            Long id, String ticketNo, String requestId, TicketAiInputSnapshotResult input,
            String summary, List<String> questions, List<String> suggestedSteps,
            String model, String promptVersion, Integer inputTokens,
            Integer outputTokens, OffsetDateTime createdAt) {
        this(id, ticketNo, requestId, input, summary, questions, suggestedSteps,
                model, promptVersion, inputTokens, outputTokens, createdAt, List.of());
    }

    /** 防御性复制问题和步骤列表，避免返回结果在构造后被外部修改。 */
    public TicketAiSuggestionResult {
        questions = List.copyOf(questions);
        suggestedSteps = List.copyOf(suggestedSteps);
        sources = List.copyOf(sources);
    }
}
