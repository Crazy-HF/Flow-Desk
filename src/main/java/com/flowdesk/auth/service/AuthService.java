package com.flowdesk.auth.service;

import com.flowdesk.auth.domain.bo.AuthServiceBO;
import com.flowdesk.auth.domain.vo.AuthLoginVO;

public interface AuthService {

    /**
     * 登录：校验凭据、建立会话并签发访问令牌。
     *
     * @param authLoginVO 已经过 {@code @Valid} 校验的登录请求
     * @return 响应体对象与 Refresh Token；后者只允许写入 Cookie，不得进入响应体
     * @throws com.flowdesk.common.exception.ApiException
     *         用户不存在、账号停用或密码错误时统一返回 {@code 401 / AUTH_INVALID_CREDENTIALS}
     */
    AuthServiceBO login(AuthLoginVO authLoginVO);
}
