package com.flowdesk.auth.security;

import com.flowdesk.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh Cookie 的组装。
 *
 * <p>HttpOnly 与 SameSite=Strict 属于安全策略，固定写在这里；路径、有效期和 Secure
 * 标志随环境变化，来自配置。</p>
 */
@Component
public class AuthCookieFactory {

    private final AuthProperties authProperties;

    public AuthCookieFactory(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** 签发用的 Refresh Cookie。 */
    public ResponseCookie refreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(authProperties.refreshCookieName(), refreshToken)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Strict")
                .path(authProperties.refreshCookiePath())
                .maxAge(authProperties.refreshExpiration())
                .build();
    }

    /** 清除用的 Refresh Cookie：同名、同路径、值为空、立即过期。 */
    public ResponseCookie clearedRefreshTokenCookie() {
        return ResponseCookie.from(authProperties.refreshCookieName(), "")
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Strict")
                .path(authProperties.refreshCookiePath())
                .maxAge(Duration.ZERO)
                .build();
    }

}
