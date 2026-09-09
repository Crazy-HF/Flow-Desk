package com.flowdesk.auth.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。密码只用于本次校验，不得写入日志。
 */
@Data
public class AuthVO {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(max = 64)
    private String password;
}
