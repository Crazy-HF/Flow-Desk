package com.flowdesk.iam.domain.vo;

import lombok.Data;

import java.util.Collection;
import java.util.List;

/**
 * <p>授权用户角色VO</p>
 */
@Data
public class GrantURVO {
    private Long userId;
    private List<Long> roleIds;
}
