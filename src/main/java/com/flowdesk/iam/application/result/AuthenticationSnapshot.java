package com.flowdesk.iam.application.result;


import com.flowdesk.iam.domain.IamUserStatus;

import java.util.List;

/** 认证查询读模型：登录所需的最小信息与授权快照。 */
public record AuthenticationSnapshot(
        Long userId,
        String username,
        String password,
        IamUserStatus status,
        List<String> roleCodes,
        List<String> permissionCodes) {
}
