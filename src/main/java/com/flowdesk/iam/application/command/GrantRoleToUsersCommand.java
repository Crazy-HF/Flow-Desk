package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量向多个用户授予同一个角色。
 */
public record GrantRoleToUsersCommand(
        @NotNull
        @Positive
        Long roleId,

        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Positive Long> userIds
) {
}
