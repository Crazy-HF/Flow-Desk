package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateUserCommand(
        @NotBlank
        @Size(max = 100)
        String displayName,

        @NotNull
        @PositiveOrZero
        Long version
) {
}
