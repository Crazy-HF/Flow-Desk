package com.flowdesk.ticket.controller;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.query.TicketAiSuggestionQuery;
import com.flowdesk.ticket.application.result.TicketAiSuggestionResult;
import com.flowdesk.ticket.application.result.TicketAiSourcesPreviewResult;
import com.flowdesk.ticket.application.service.TicketAiSuggestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

/**
 * @ClassName TicketAiSuggestionController
 * @Description TODO
 * @Author xb.m
 * @Date 2026/10/23 23:01
 * @Version 1.0
 */

/**
 * 单张工单的 AI 建议生成与历史入口。
 * 权限、工单可见性、负责人关系和额度均由应用服务重新校验。
 */
@RestController
@RequestMapping("/fd/v1/tickets/{ticketNo}/ai-suggestions")
public class TicketAiSuggestionController {

    private static final Set<String> HISTORY_PARAMETERS =
            Set.of("pageNo", "pageSize", "orderBy", "orderDirection");

    private final TicketAiSuggestionService suggestionService;

    public TicketAiSuggestionController(
            TicketAiSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    /**
     * 接收负责人再次确认后的标题、描述、工单版本和 requestId。
     * 分类、优先级、状态由服务端读取，不接受客户端替代值。
     */
    @PostMapping
    public R<TicketAiSuggestionResult> generate(
            @PathVariable String ticketNo,
            @Valid @RequestBody GenerateTicketAiSuggestionCommand command) {
        return R.success(suggestionService.generate(ticketNo, command));
    }

    /** 返回本次确认前的来源快照；后续生成时仍必须重新查询并校验摘要。 */
    @GetMapping("/sources-preview")
    public R<TicketAiSourcesPreviewResult> previewSources(
            @PathVariable String ticketNo,
            @RequestParam Long version) {
        return R.success(suggestionService.previewSources(ticketNo, version));
    }

    /**
     * 分页查询该工单的受限 AI 历史。
     * 不要求当前仍持有 AI 生成权限；具体可见性由服务层判断。
     */
    @GetMapping
    public R<PageResult<TicketAiSuggestionResult>> history(
            @PathVariable String ticketNo,
            @Valid @ModelAttribute TicketAiSuggestionQuery query,
            HttpServletRequest request) {
        rejectUnsupportedParameters(request);
        return R.success(suggestionService.history(ticketNo, query));
    }

    /**
     * 拒绝未知查询参数，防止客户端误以为 userId、status 等筛选已生效。
     * 排序字段及方向是否允许，继续由 TicketAiSuggestionQuery 校验。
     */
    private static void rejectUnsupportedParameters(
            HttpServletRequest request) {
        for (String name : Collections.list(request.getParameterNames())) {
            if (!HISTORY_PARAMETERS.contains(name)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "AI 建议历史不支持该查询参数");
            }
        }
    }
}
