package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateRoleCommand(
        @NotBlank
        @Size(max = 50)
        String name,
        @Size(max = 255)
        String description ){
}
