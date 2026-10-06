package com.flowdesk.ticket.application.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单列表与时间线的排序约束只有一套口径。
 *
 * <p>两个查询对象都用 {@code @AssertTrue} 把"只允许固定升序"表达成 Bean Validation 约束。
 * 约束此前用裸字符串比较（{@code "asc".equals(getOrderDirection())}），比
 * {@link com.flowdesk.common.web.PageQuery} 自己的判定更严格：{@code orderDirection=ASC}
 * 会被 400 拒绝，而同一个查询对象在 {@code orderItems} 里是接受大写方向的。现在两者都走
 * {@code PageQuery.isAscendingDirection()}，本类把该口径固定下来：空值按默认升序处理、
 * 方向比较忽略大小写、非空且非 asc/desc 一律拒绝。</p>
 *
 * <p>这里直接调用约束方法，不启动 Spring 上下文：Web 层的行为由
 * {@code TicketControllerWebTest} 覆盖，本类只锁定判定表本身。</p>
 */
class TicketQuerySortTest {

    /** 覆盖 null、空串、大小写、非法值与显式 orderBy 五类输入。 */
    static Stream<Arguments> sortParameters() {
        return Stream.of(
                Arguments.of("未传方向", null, null, true),
                Arguments.of("空串方向", "", null, true),
                Arguments.of("小写 asc", "asc", null, true),
                Arguments.of("大写 ASC", "ASC", null, true),
                Arguments.of("混合大小写 aSc", "aSc", null, true),
                Arguments.of("小写 desc", "desc", null, false),
                Arguments.of("大写 DESC", "DESC", null, false),
                Arguments.of("非法方向", "sideways", null, false),
                Arguments.of("显式 orderBy", "asc", "created_at", false),
                Arguments.of("空的 orderBy 视为未请求排序", "asc", "", true));
    }

    @ParameterizedTest(name = "工单列表：{0} → 允许={3}")
    @MethodSource("sortParameters")
    void ticketListAcceptsOnlyFixedAscending(
            String caseName, String orderDirection, String orderBy, boolean allowed) {
        TicketQuery query = new TicketQuery();
        query.setOrderDirection(orderDirection);
        query.setOrderBy(orderBy);

        assertThat(query.isFixedSortOnly()).as(caseName).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "时间线：{0} → 允许={3}")
    @MethodSource("sortParameters")
    void ticketRecordsAcceptOnlyFixedAscending(
            String caseName, String orderDirection, String orderBy, boolean allowed) {
        TicketRecordQuery query = new TicketRecordQuery();
        query.setOrderDirection(orderDirection);
        query.setOrderBy(orderBy);

        assertThat(query.isFixedOrderOnly()).as(caseName).isEqualTo(allowed);
    }

    /** 两个查询对象的默认值必须自己满足约束：客户端不传排序参数时不能先被 400 拦住。 */
    @Test
    void defaultsSatisfyTheFixedAscendingConstraint() {
        TicketQuery listQuery = new TicketQuery();
        TicketRecordQuery recordsQuery = new TicketRecordQuery();

        assertThat(listQuery.getOrderDirection()).isEqualTo("asc");
        assertThat(recordsQuery.getOrderDirection()).isEqualTo("asc");
        assertThat(listQuery.isFixedSortOnly()).isTrue();
        assertThat(recordsQuery.isFixedOrderOnly()).isTrue();
    }
}
