package com.flowdesk.iam.application.result;

import java.time.OffsetDateTime;
import java.util.List;

public record RoleResult(
        Long id,
        String code,
        String name,
        String description,
        OffsetDateTime createdAt,
        List<Long> permissionIds){

}
