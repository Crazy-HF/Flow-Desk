package com.flowdesk.ticket.application.port;

/** 读取本次已认证请求的业务权限，不让工单服务直接依赖认证实现。 */
public interface TicketReadPermissionPort {
    boolean hasAuthority(String authority);
}
