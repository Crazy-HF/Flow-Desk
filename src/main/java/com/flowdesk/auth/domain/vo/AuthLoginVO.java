package com.flowdesk.auth.domain.vo;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。字段规则来自 {@code docs/api-design.md} 3.2；越界输入由 Controller 层的
 * {@code @Valid} 拦下，服务层不再重复校验。
 */
public record AuthLoginVO(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 64) String password){

    public AuthLoginVO {
        // 只归一化登录名；密码必须原样参与校验
        username = username == null ? null : username.trim();
    }

    /** 覆写以脱敏密码：record 默认生成的实现会打印明文。 */
    @Override
    public String toString() {
        return "AuthLoginVO[username=" + username + ", password=***]";
    }
}
