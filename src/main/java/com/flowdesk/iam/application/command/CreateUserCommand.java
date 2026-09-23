package com.flowdesk.iam.application.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateUserCommand(

        @NotBlank
        @Size(max = 64)
        String username,

        @NotBlank
        @Size(max = 100)
        String displayName,

        @NotBlank
        @Size(min = 8, max = 64)
        String initialPassword,

        @Valid
        List<@Positive Long> roleIds
) {
}