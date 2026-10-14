package com.flowdesk.iam.application.port;

/**
 * 当前操作者端口
 */
public interface CurrentOperatorPort {
    /**获取当前用户ID*/
    long currentUserId();

    /**
     * 当前请求是否持有某个业务权限。
     *
     * <p>给「条件性」权限判断使用：停用账号与替换角色只有在<strong>真的发生管理性交接</strong>时才要求
     * {@code TICKET_ADMIN_HANDOFF}（{@code docs/api-design.md} 8.3）；目标用户手上没有活动工单时，
     * 8.2 原有的 {@code USER_MANAGE} 契约保持不变。接口级权限仍由 {@code @PreAuthorize} 在控制器上拦，
     * 这里只补"按数据状态决定"的那一层。</p>
     */
    boolean hasAuthority(String authority);
}
