package com.flowdesk.iam.domain.bo;

import java.time.OffsetDateTime;
import java.util.List;

public record IamPermissionBO(
        Long id,
        String code,
        String name,
        String description,
        OffsetDateTime createdAt,
        List<Long> roleIds
) {
}
