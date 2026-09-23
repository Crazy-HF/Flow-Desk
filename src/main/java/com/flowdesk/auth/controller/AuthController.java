package com.flowdesk.auth.controller;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.application.result.LoginResult;
import com.flowdesk.auth.application.result.IssuedSessionResult;
import com.flowdesk.auth.application.result.AuthenticatedUserResult;
import com.flowdesk.auth.application.command.ChangePasswordCommand;
import com.flowdesk.auth.application.command.LoginCommand;
import com.flowdesk.auth.security.AuthCookieFactory;
import com.flowdesk.auth.application.service.AuthService;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.R;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.util.Locale;

@RestController
@RequestMapping("/fd/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;
    private final AuthProperties authProperties;

    public AuthController(AuthService authService, AuthCookieFactory authCookieFactory, AuthProperties authProperties) {
        this.authService = authService;
        this.authCookieFactory = authCookieFactory;
        this.authProperties = authProperties;
    }

    /**
     * 登录：Access Token 与最小身份信息走响应体，Refresh Token 只写入 HttpOnly Cookie。
     *
     * <p>失败统一返回 {@code 401 / AUTH_INVALID_CREDENTIALS}，不区分用户不存在、账号停用或密码错误。</p>
     */
    @PostMapping("/login")
    public ResponseEntity<R<LoginResult>> login(@Valid @RequestBody LoginCommand loginCommand) {
        IssuedSessionResult result = authService.login(loginCommand);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        authCookieFactory.refreshTokenCookie(result.refreshToken()).toString())
                .body(R.success(result.response()));
    }

    /**
     * 刷新：从 HttpOnly Cookie 读 Refresh Token，轮换后返回新的 Access Token 并覆盖 Cookie。
     *
     * <p>失败统一返回 {@code 401 / AUTH_SESSION_INVALID}；来源不在白名单时返回 {@code 403 / ORIGIN_NOT_ALLOWED}。</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<R<LoginResult>> refresh(HttpServletRequest request,
                                                  @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        validateOrigin(origin);
        IssuedSessionResult result = authService.refresh(readRefreshCookie(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        authCookieFactory.refreshTokenCookie(result.refreshToken()).toString())
                .body(R.success(result.response()));
    }

    /**
     * 依赖 Cookie 的接口必须校验来源：跨站页面不能拿着用户的 Cookie 触发轮换或退出。
     * 缺失 Origin 同样拒绝——浏览器对这类请求一定会带上它。
     */
    private void validateOrigin(String origin) {
        if (origin == null || !authProperties.allowedOrigins().contains(origin.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "请求来源不被允许");
        }
    }

    /** Cookie 名来自配置，所以不用注解写死。 */
    private String readRefreshCookie(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, authProperties.refreshCookieName());
        return cookie == null ? null : cookie.getValue();
    }


    /**
     * 退出：按 Cookie 撤销会话，并且永远清除 Cookie。
     *
     * <p>重复调用保持成功，不泄露会话是否存在过。</p>
     */
    @PostMapping("/logout")
    public ResponseEntity<R<Void>> logout(HttpServletRequest request,
                                          @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        validateOrigin(origin);
        authService.logout(readRefreshCookie(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.clearedRefreshTokenCookie().toString())
                .body(R.success());
    }


    /**
     * 当前身份：前端刷新页面后用它恢复菜单与路由所需的信息。
     */
    @GetMapping("/me")
    public R<AuthenticatedUserResult> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return R.success(authService.currentUser(principal));
    }

    /**
     * 修改本人密码：校验原密码，成功后撤销该用户全部会话并清除 Refresh Cookie。
     *
     * <p>当前会话也在撤销范围内，所以调用方拿到成功响应后要回到登录页。</p>
     */
    @PostMapping("/change-password")
    public ResponseEntity<R<Void>> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @Valid @RequestBody ChangePasswordCommand command) {
        authService.changePassword(principal, command);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.clearedRefreshTokenCookie().toString())
                .body(R.success());
    }

}
