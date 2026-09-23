package com.flowdesk.iam.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RolePermissionQuery extends PageQuery {

    @Positive
    private Long roleId;

    @Positive
    private Long permissionId;

    @AssertTrue(message = "roleId 或 permissionId 至少提供一个")
    public boolean isFilterPresent() {
        return roleId != null || permissionId != null;
    }
}
