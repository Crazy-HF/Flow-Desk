package com.flowdesk.iam.application.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量撤销一个角色的多个权限。
 */
public record RevokeRolePermissionsCommand(
        @NotNull
        @Positive
        Long roleId,

        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Positive Long> permissionIds
) {
}
