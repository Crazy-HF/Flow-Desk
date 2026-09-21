package com.flowdesk.iam.domain.vo;

import com.flowdesk.common.web.PageQuery;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IamRoleQueryVO extends PageQuery {

    private String keyword;
}
