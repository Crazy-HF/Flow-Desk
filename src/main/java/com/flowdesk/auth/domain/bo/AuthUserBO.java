package com.flowdesk.auth.domain.bo;

import java.util.List;

/** 登录成功后返回给前端的最小身份信息。 */
public record AuthUserBO(
        Long id,
        String username,
        String displayName,
        List<String> roles,
        List<String> permissions
) {
    public AuthUserBO {
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
