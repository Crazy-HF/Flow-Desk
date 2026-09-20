package com.flowdesk.auth.domain.bo;

import java.util.List;

/**
 * 最小身份信息：登录响应与 {@code GET /fd/v1/auth/me} 共用。
 *
 * <p>角色与权限只用于前端界面展示和路由判断，后端仍对每次请求重新执行授权校验。</p>
 */
public record AuthUserBO(
        Long id,
        String username,
        String displayName,
        List<String> roles,
        List<String> permissions) {
}
