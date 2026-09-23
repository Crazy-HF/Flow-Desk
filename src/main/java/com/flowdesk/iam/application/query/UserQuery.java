package com.flowdesk.iam.application.query;

import com.flowdesk.common.web.PageQuery;
import com.flowdesk.iam.domain.IamUserStatus;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserQuery extends PageQuery {

    /**
     * 同时匹配 username 和 displayName。
     */
    private String keyword;

    private IamUserStatus status;

    /**
     * 只查询拥有指定角色的用户。
     */
    @Positive
    private Long roleId;
}