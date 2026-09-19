package com.flowdesk.auth.domain.vo;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改本人密码的请求体。
 *
 * <p>原密码用于确认是本人操作；新密码只在原密码校验通过后才会哈希保存。</p>
 */
public record AuthChangePwdVO(
        @NotBlank @Size(max = 64) String currentPassword,
        @NotBlank @Size(min = 6, max = 64) String newPassword) {
}
