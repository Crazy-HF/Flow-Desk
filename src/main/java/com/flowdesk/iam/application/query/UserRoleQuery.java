package com.flowdesk.iam.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRoleQuery extends PageQuery {

    /**只校验已提供的 ID，不会拒绝 null*/
    @Positive
    private Long userId;

    @Positive
    private Long roleId;

    @AssertTrue(message = "userId 或 roleId 至少提供一个")
    public boolean isFilterPresent() {
        return userId != null || roleId != null;
    }
}