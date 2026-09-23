package com.flowdesk.auth.application.result;

/** 登录服务结果：{@code response} 进响应体，{@code refreshToken} 只写入 Cookie。 */
public record IssuedSessionResult(
        LoginResult response, String refreshToken) {
}
