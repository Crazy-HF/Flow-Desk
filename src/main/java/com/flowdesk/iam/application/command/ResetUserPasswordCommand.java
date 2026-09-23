package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ResetUserPasswordCommand(
        @NotNull
        @PositiveOrZero
        Long version,

        @NotBlank
        @Size(min = 8, max = 64)
        String newPassword
) {
}