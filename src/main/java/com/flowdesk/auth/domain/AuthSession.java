package com.flowdesk.auth.domain;

import java.time.Instant;
import java.util.List;

/**
 * Redis 中的登录会话快照。由同一会话派生的三个键共用同一有效期：
 * {@code flowdesk:auth:session:{sessionId}} 保存本对象，
 * {@code flowdesk:auth:refresh:{refreshDigest}} 供刷新时按摘要反查会话，
 * {@code flowdesk:auth:user:{userId}} 汇总该用户的会话标识以便整体撤销。
 *
 * <p>Refresh Token 原文不在此对象中，只保存摘要；角色与权限随快照保存，
 * 因此撤销会话即可让账号停用或权限变更立即生效。</p>
 */
public record AuthSession(
        String sessionId,
        Long userId,
        String username,
        String displayName,
        String refreshDigest,
        List<String> roleCodes,
        List<String> permissionCodes,
        Instant createdAt,
        Instant expiresAt) {

    /** 轮换后返回同一会话的新快照：只有 Refresh 摘要变化，其余字段保持不变。 */
    public AuthSession withRefreshDigest(String refreshDigest) {
        return new AuthSession(sessionId, userId, username, displayName, refreshDigest, roleCodes,
                permissionCodes, createdAt, expiresAt);
    }
}
