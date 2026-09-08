package com.flowdesk.iam.domain.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员重置用户密码请求；明文密码只用于本次请求，不得记录或返回。 */
@Data
public class IamUserResetPasswordVO {

    @NotBlank
    @Size(min = 8, max = 64)
    private String newPassword;

    @NotNull
    @Min(0)
    private Long version;
}
