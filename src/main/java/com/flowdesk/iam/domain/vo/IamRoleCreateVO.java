package com.flowdesk.iam.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IamRoleCreateVO(

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
        String code,

        @NotBlank
        @Size(max = 50)
        String name,

        @Size(max = 255)
        String description) {
}
