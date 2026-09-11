package com.flowdesk.iam.domain.bo;

import com.flowdesk.iam.domain.IamUserStatus;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * IAM 提供给认证模块的内部身份快照，不得作为接口响应直接返回。
 */
@Data
public class IamAuthenticationBO {

    private Long id;
    private String username;
    private String displayName;
    private String passwordHash;
    private IamUserStatus status;
    private Set<String> roleCodes = new LinkedHashSet<>();
    private Set<String> permissionCodes = new LinkedHashSet<>();
}
