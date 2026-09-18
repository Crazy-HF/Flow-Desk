package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthSession;

/**
 * 登录会话的持久化出口。调用方只依赖本接口，不感知数据实际存在 Redis。
 */
public interface AuthSessionRepository {
    /** 写入会话、Refresh 摘要索引与该用户的会话集合，三个键共用同一有效期。 */
    void save(AuthSession session);
}