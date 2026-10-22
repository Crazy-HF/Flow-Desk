package com.flowdesk.ticket.controller;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.R;
import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.application.result.TicketDashboardResult;
import com.flowdesk.ticket.application.service.TicketDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

/**
 * 数据概览接口（{@code docs/api-design.md} 8.5）。
 *
 * <p>刻意独立于 {@link TicketController}：它是只读聚合，不参与工单状态机，
 * 路径前缀也不同（{@code /fd/v1/dashboard}）。放在同一个控制器里会让"哪些端点会写工单"更难判断。</p>
 *
 * <p>接口级权限只声明 {@code DASHBOARD_VIEW}；{@code IT_SUPPORT} 身份与选中范围的查看权限由
 * 服务层判定——它们依赖范围参数，写进 SpEL 会把授权规则散成两处。</p>
 */
@RestController
@RequestMapping("/fd/v1/dashboard")
public class TicketDashboardController {

    /**
     * 概览接受的查询参数。
     *
     * <p>白名单之外一律 {@code 400}，不静默忽略：{@code userId} / {@code assigneeId} / {@code teamId}
     * 被忽略会让调用方误以为"按他人过滤"生效了，{@code page} / {@code status} / {@code sort}
     * 被忽略会让界面以为分页或筛选在起作用。{@code @ModelAttribute} 默认丢弃未知参数，
     * 因此这里显式检查——这是本接口唯一一处自己做的参数收口。</p>
     */
    private static final Set<String> SUPPORTED_PARAMETERS =
            Set.of("scope", "createdFrom", "createdTo", "categoryId");

    private final TicketDashboardService ticketDashboardService;

    public TicketDashboardController(TicketDashboardService ticketDashboardService) {
        this.ticketDashboardService = ticketDashboardService;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public R<TicketDashboardResult> overview(
            @Valid @ModelAttribute TicketDashboardQuery query,
            HttpServletRequest request) {
        rejectUnsupportedParameters(request);
        return R.success(ticketDashboardService.overview(query));
    }

    private void rejectUnsupportedParameters(HttpServletRequest request) {
        for (String name : Collections.list(request.getParameterNames())) {
            if (!SUPPORTED_PARAMETERS.contains(name)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "数据概览不支持该查询参数");
            }
        }
    }
}
