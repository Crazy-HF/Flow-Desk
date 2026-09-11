package com.flowdesk.auth.domain.bo;

/** 登录成功响应；Refresh Token 后续只通过 HttpOnly Cookie 返回。 */
public record AuthLoginBO(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthUserBO user
) {
    public static final String BEARER = "Bearer";

    public AuthLoginBO(String accessToken, long expiresIn, AuthUserBO user) {
        this(accessToken, BEARER, expiresIn, user);
    }
}
