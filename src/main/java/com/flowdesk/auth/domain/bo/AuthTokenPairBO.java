package com.flowdesk.auth.domain.bo;

/** 登录时创建的一组令牌；Refresh Token 不得写入响应体或日志。 */
public record AuthTokenPairBO(
        String accessToken,
        String refreshToken,
        String sessionId
) {
}
