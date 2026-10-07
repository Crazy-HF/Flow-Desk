package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 转交候选人查询的扁平投影，只包含选择新负责人所需的两个字段。
 *
 * <p>投影而不是实体：候选人来自 {@code iam_user}，工单模块只借用标识与显示名称，
 * 不需要（也不应该）读取账号的其它列。</p>
 */
@Data
public class TicketAssigneeRow {
    private Long id;
    private String displayName;
}
