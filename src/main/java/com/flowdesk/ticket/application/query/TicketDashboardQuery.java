package com.flowdesk.ticket.application.query;

import com.flowdesk.ticket.domain.TicketScope;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

/**
 * 数据概览的查询条件（{@code docs/api-design.md} 8.5）。
 *
 * <p>与 {@code TicketQuery} 的关键差别是**没有分页、状态、优先级、排序与关键词**：
 * 概览只回答"我能看的工单有多少、分布如何"，因此客户端不能自选状态、优先级、负责人或排序，
 * 任何这类参数都由控制器按不支持参数拒绝（{@code TicketDashboardController}），而不是静默忽略。</p>
 *
 * <p>{@code scope} 必填且只接受三个 IT 范围：{@code REQUESTED_BY_ME} 是员工视角，
 * 概览没有它的语义；三个范围也不合并成"全部可见"——「我负责的」与「我参与的」有重叠，
 * 并集需要另一套去重与授权规则（见设计文档的方案取舍）。</p>
 */
@Getter
@Setter
public class TicketDashboardQuery {

    @NotNull(message = "查询范围不能为空")
    private TicketScope scope;

    /** 创建时间下界（含），带偏移量的 ISO 8601；未传表示不限制。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdFrom;

    /** 创建时间上界（含）；未传表示不限制，不暗设"近 30 天"。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdTo;

    /** 当前分类；合法但无匹配工单返回空统计，停用分类不视为无效。 */
    @Positive
    private Long categoryId;

    @AssertTrue(message = "创建时间起点不能晚于终点")
    public boolean isCreatedRangeValid() {
        return createdFrom == null || createdTo == null
                || !createdFrom.toInstant().isAfter(createdTo.toInstant());
    }

    /**
     * 只接受三个 IT 范围。
     *
     * <p>为空时由 {@link #scope} 的 {@code @NotNull} 负责，这里返回 {@code true}，
     * 避免同一个错误产生两条字段错误。</p>
     */
    @AssertTrue(message = "数据概览只支持 PENDING_QUEUE、ASSIGNED_TO_ME 与 PARTICIPATED_BY_ME")
    public boolean isSupportedScope() {
        return scope == null || scope != TicketScope.REQUESTED_BY_ME;
    }
}
