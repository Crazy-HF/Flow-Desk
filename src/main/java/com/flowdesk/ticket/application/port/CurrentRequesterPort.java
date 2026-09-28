package com.flowdesk.ticket.application.port;

public interface CurrentRequesterPort {

    /**获取当前用户ID*/
    long currentUserId();
}