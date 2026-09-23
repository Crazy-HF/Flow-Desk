package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量撤销一个用户的多个角色。
 */
public record RevokeUserRolesCommand(
        @NotNull
        @Positive
        Long userId,

        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Positive Long> roleIds
) {
}
