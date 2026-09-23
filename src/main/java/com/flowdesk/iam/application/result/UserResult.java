package com.flowdesk.iam.application.result;

import com.flowdesk.iam.domain.IamUserStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record UserResult(
        Long id,
        String username,
        String displayName,
        IamUserStatus status,
        List<UserRoleSummaryResult> roles,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version
) {
}