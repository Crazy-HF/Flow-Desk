package com.flowdesk.iam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Role-to-permission grant persisted in {@code iam_role_permission}.
 */
@Data
@TableName("iam_role_permission")
public class IamRolePermission {

    private Long roleId;
    private Long permissionId;
    private Long grantedBy;
    private LocalDateTime grantedAt;
}
