package com.flowdesk.iam.application.result;

import java.time.OffsetDateTime;

public record UserRoleResult(
        Long userId,
        String username,
        Long roleId,
        String roleCode,
        String roleName,
        Long grantedBy,
        OffsetDateTime grantedAt) {
}