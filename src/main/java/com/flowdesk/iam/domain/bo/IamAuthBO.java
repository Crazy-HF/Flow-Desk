package com.flowdesk.iam.domain.bo;


import com.flowdesk.iam.domain.IamUserStatus;

import java.util.List;

/** 认证查询读模型：登录所需的最小信息与授权快照。 */
public record IamAuthBO(
        Long userId,
        String username,
        String displayName,
        String password,
        IamUserStatus status,
        List<String> roleCodes,
        List<String> permissionCodes) {
}
