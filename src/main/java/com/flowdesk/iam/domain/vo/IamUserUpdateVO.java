package com.flowdesk.iam.domain.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员修改用户基本资料的请求。登录名、状态和角色通过各自专用用例处理。 */
@Data
public class IamUserUpdateVO {

    @NotBlank
    @Size(max = 100)
    private String displayName;

    @NotNull
    @Min(0)
    private Long version;
}
