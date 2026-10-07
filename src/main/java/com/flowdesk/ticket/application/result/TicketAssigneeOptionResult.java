package com.flowdesk.ticket.application.result;

/**
 * 转交候选人选项，只暴露选择新负责人所需的字段（`docs/api-design.md` 7.4）。
 *
 * <p>不返回用户名、角色或其它账号信息：转交只需要标识和显示名称，专用最小字段接口
 * 不替代通用用户搜索。</p>
 */
public record TicketAssigneeOptionResult(
        Long id,
        String displayName) {
}
