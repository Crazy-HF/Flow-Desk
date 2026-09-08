package com.flowdesk.iam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * User-to-role grant persisted in {@code iam_user_role}.
 */
@Data
@TableName("iam_user_role")
public class IamUserRole {

    private Long userId;
    private Long roleId;
    private Long grantedBy;
    private LocalDateTime grantedAt;
}
