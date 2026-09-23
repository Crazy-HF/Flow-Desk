package com.flowdesk.iam.application.command;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UserStatusChangeCommand(
        @NotNull
        @PositiveOrZero
        Long version
) {
}
