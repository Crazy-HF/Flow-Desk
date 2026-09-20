package com.flowdesk.auth.domain;

/**
 * Refresh 摘要的查询结果，三种状态互斥：
 * ACTIVE 可以轮换，REUSED 是已经轮换过的旧令牌（重放），UNKNOWN 表示从未存在或已经过期。
 *
 * <p>REUSED 必须能被区分出来：它意味着令牌已经泄露给了第三方，
 * 处理方式是撤销整个会话，而不是简单拒绝这一次刷新。</p>
 */
public record RefreshTokenLookup(Status status, String sessionId) {

    /** Refresh 摘要的状态。 */
    public enum Status { ACTIVE, REUSED, UNKNOWN }

    /** 新令牌：调用方需要按会话标识创建新的令牌。 */
    public static RefreshTokenLookup active(String sessionId) {
        return new RefreshTokenLookup(Status.ACTIVE, sessionId);
    }

    /** 旧令牌重放：调用方需要按会话标识撤销整个会话。 */
    public static RefreshTokenLookup reused(String sessionId) {
        return new RefreshTokenLookup(Status.REUSED, sessionId);
    }

    /** 未知状态：调用方需要按会话标识创建新的令牌。 */
    public static RefreshTokenLookup unknown() {
        return new RefreshTokenLookup(Status.UNKNOWN, null);
    }
}