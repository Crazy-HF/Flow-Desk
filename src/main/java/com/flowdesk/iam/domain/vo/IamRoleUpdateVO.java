package com.flowdesk.iam.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IamRoleUpdateVO(
        @NotBlank
        @Size(max = 50)
        String name,
        @Size(max = 255)
        String description ){
}
