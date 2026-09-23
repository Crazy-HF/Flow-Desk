package com.flowdesk.iam.application.result;

import java.time.OffsetDateTime;

public record RolePermissionResult(
        Long roleId,
        String roleCode,
        Long permissionId,
        String permissionCode,
        String permissionName,
        Long grantedBy,
        OffsetDateTime grantedAt
) {
}
