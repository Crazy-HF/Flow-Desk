package com.flowdesk.auth.domain;


/**
 * 请求认证后的身份视图：由 JWT 请求认证过滤器写入 Spring Security 上下文，供控制器读取。
 *
 * <p>只保留定位用户与展示所需的字段；{@link AuthSession} 中的 {@code refreshDigest}
 * 和会话时间属于会话存储细节，不进入本对象。</p>
 */
/**只确保当前是谁 */
public record AuthPrincipal(
        long userId,
        String username,
        String sessionId
) {
}
