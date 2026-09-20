package com.flowdesk.auth.domain.bo;

/** 登录服务结果：{@code response} 进响应体，{@code refreshToken} 只写入 Cookie。 */
public record AuthServiceBO(
        AuthLoginBO response, String refreshToken) {
}
