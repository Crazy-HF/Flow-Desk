package com.flowdesk.notification.application.port;

/**
 * 当前收件人身份出口：站内信模块定义，由认证模块的 {@code infrastructure} 实现，
 * 站内信模块因此不感知认证实现（跨模块出口定义在被依赖方模块、实现放调用方模块）。
 */
public interface CurrentRecipientPort {

    /** 当前登录用户 ID；未认证时由实现方抛出 401 语义异常。 */
    long currentUserId();
}
