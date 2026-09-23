package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRoleCommand(

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
        String code,

        @NotBlank
        @Size(max = 50)
        String name,

        @Size(max = 255)
        String description,

        @Size(max = 100)
        List<@NotNull @Positive Long> permissionIds) {
}
