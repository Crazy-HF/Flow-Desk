package com.flowdesk.iam.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PermissionQuery extends PageQuery {
    @Size(max = 100, message = "查询关键词最大长度为100")
    private String keyword;
}
