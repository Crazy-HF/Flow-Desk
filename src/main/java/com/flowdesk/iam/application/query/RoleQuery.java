package com.flowdesk.iam.application.query;

import com.flowdesk.common.web.PageQuery;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleQuery extends PageQuery {

    private String keyword;
}
