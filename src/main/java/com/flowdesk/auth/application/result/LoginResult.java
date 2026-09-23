package com.flowdesk.auth.application.result;

/**
 * 登录成功响应体（{@code docs/api-design.md} 3.2）。
 *
 * <p>Refresh Token 只通过 HttpOnly Cookie 返回，不出现在这里。</p>
 */
public record LoginResult(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthenticatedUserResult user) {

    public static final String BEARER = "Bearer";
}
