package com.flowdesk.iam.application.port;

/**
 * 当前操作者端口
 */
public interface CurrentOperatorPort {
    /**获取当前用户ID*/
    long currentUserId();
}
