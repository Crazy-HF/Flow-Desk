package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.RefreshTokenLookup;

import java.util.Optional;

/**
 * 登录会话的持久化出口。调用方只依赖本接口，不感知数据实际存在 Redis。
 */
public interface AuthSessionRepository {
    /** 写入会话、Refresh 摘要索引与该用户的会话集合，三个键共用同一有效期。 */
    void save(AuthSession session);

    /** 根据会话 ID 查询会话。 */
    Optional<AuthSession> findById(String sessionId);


    /** 按 Refresh 摘要查询状态：活跃、已轮换过的旧令牌，或不存在。 */
    RefreshTokenLookup findByRefreshDigest(String refreshDigest);

    /**
     * 轮换 Refresh Token：把旧摘要标记为已使用、写入新摘要索引，并更新会话快照中的摘要。
     *
     * <p>新索引沿用所属会话的剩余有效期，轮换不延长会话寿命。</p>
     */
    void rotate(AuthSession session, String previousDigest);

    /** 撤销会话：删除会话与刷新索引，并从用户会话集合中移除；会话已不存在时不做任何事。 */
    void revoke(String sessionId);

    /** 撤销该用户的全部会话：按用户会话集合逐个撤销，并清理集合本身。 */
    void revokeAll(long userId);
}
