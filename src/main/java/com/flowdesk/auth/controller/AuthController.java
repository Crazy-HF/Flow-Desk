package com.flowdesk.auth.controller;

import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthServiceBO;
import com.flowdesk.auth.domain.vo.AuthLoginVO;
import com.flowdesk.auth.security.AuthCookieFactory;
import com.flowdesk.auth.service.AuthService;
import com.flowdesk.common.web.R;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fd/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;

    public AuthController(AuthService authService, AuthCookieFactory authCookieFactory) {
        this.authService = authService;
        this.authCookieFactory = authCookieFactory;
    }

    /**
     * 登录：Access Token 与最小身份信息走响应体，Refresh Token 只写入 HttpOnly Cookie。
     *
     * <p>失败统一返回 {@code 401 / AUTH_INVALID_CREDENTIALS}，不区分用户不存在、账号停用或密码错误。</p>
     */
    @PostMapping("/login")
    public ResponseEntity<R<AuthLoginBO>> login(@Valid @RequestBody AuthLoginVO authLoginVO) {
        AuthServiceBO result = authService.login(authLoginVO);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        authCookieFactory.refreshTokenCookie(result.refreshToken()).toString())
                .body(R.success(result.response()));
    }
}
