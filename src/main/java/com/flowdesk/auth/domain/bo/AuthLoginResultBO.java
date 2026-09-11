package com.flowdesk.auth.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 登录服务的内部结果。Refresh Token 只交给 Controller 写入 HttpOnly Cookie，
 * 不作为 JSON 响应对象使用。
 */
public record AuthLoginResultBO(
        AuthLoginBO response,
        @JsonIgnore String refreshToken
) {
}
