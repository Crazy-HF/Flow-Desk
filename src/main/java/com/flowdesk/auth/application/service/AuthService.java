package com.flowdesk.auth.application.service;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.application.command.ChangePasswordCommand;
import com.flowdesk.auth.application.result.IssuedSessionResult;
import com.flowdesk.auth.application.result.AuthenticatedUserResult;
import com.flowdesk.auth.application.command.LoginCommand;

public interface AuthService {

    /**
     * 登录：校验凭据、建立会话并签发访问令牌。
     *
     * @param loginCommand 已经过 {@code @Valid} 校验的登录请求
     * @return 响应体对象与 Refresh Token；后者只允许写入 Cookie，不得进入响应体
     * @throws com.flowdesk.common.exception.ApiException
     *         用户不存在、账号停用或密码错误时统一返回 {@code 401 / AUTH_INVALID_CREDENTIALS}
     */
    IssuedSessionResult login(LoginCommand loginCommand);

    /**
     * 刷新令牌：用 Refresh Token 换新的 Access Token，并轮换 Refresh Token。
     *
     * @param refreshToken 来自 HttpOnly Cookie 的原始令牌；缺失时同样按失败处理
     * @return 响应体对象与新的 Refresh Token；后者只允许写入 Cookie
     * @throws com.flowdesk.common.exception.ApiException
     *         令牌缺失、无效、过期、已撤销或检测到重放时返回 {@code 401 / AUTH_SESSION_INVALID}
     */
    IssuedSessionResult refresh(String refreshToken);

    /**
     * 退出登录：撤销 Refresh Token 对应的会话。
     *
     * <p>基于 Cookie 而不是 {@code SecurityContext}：用户点退出时手里的短票可能已经失效，
     * 那时上下文里是空的，但退出仍然必须成功。</p>
     *
     * @param refreshToken 来自 HttpOnly Cookie 的原始令牌；缺失或已失效都算退出成功
     */
    void logout(String refreshToken);

    /**
     * 当前身份：角色与权限取会话快照，供前端初始化菜单与路由。
     *
     * <p>这些信息只用于界面展示，后端仍对每次请求独立执行授权校验。</p>
     *
     * @throws com.flowdesk.common.exception.ApiException
     *         会话已不存在或已过期时返回 {@code 401 / AUTH_SESSION_INVALID}
     */
    AuthenticatedUserResult currentUser(AuthPrincipal principal);

    /**
     * 修改本人密码：校验原密码，成功后撤销该用户全部会话。
     *
     * <p>顺序是先撤销会话（Redis）再写入新密码（MySQL）：反过来一旦 Redis 撤销失败，
     * 就会留下"密码已改但旧会话仍可用"的状态；先撤销的最坏结果只是用户需要用旧密码重新登录。</p>
     *
     * @throws com.flowdesk.common.exception.ApiException
     *         原密码不正确或账号不可用时返回 {@code 401 / AUTH_INVALID_CREDENTIALS}；
     *         写入时版本冲突返回 {@code 409 / USER_CONFLICT}
     */
    void changePassword(AuthPrincipal principal, ChangePasswordCommand command);
}
