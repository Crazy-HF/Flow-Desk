package com.flowdesk.iam.application.result;

import com.flowdesk.iam.domain.IamUserStatus;

public record UserProfileSnapshot(
        Long userId,
        String username,
        String displayName,
        IamUserStatus status
) {
}
