package com.flowdesk.iam.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建用户请求；password 是明文输入，只用于本次请求，不得记录或返回。 */
@Data
public class IamUserCreateVO {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(max = 100)
    private String displayName;

    @NotBlank
    @Size(min = 8, max = 64)
    private String password;
}
