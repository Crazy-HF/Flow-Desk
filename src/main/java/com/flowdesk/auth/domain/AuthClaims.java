package com.flowdesk.auth.domain;

import java.time.Instant;

/** 从 Access Token 解析出的请求身份：用户标识、会话标识与到期时刻。 */
public record AuthClaims(long userId, String sessionId, Instant expiresAt) {
}
