package com.flowdesk.auth.controller;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthLoginResultBO;
import com.flowdesk.auth.domain.vo.AuthVO;
import com.flowdesk.auth.service.AuthLoginService;
import com.flowdesk.shared.web.R;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份认证接口入口。
 *
 * <p>Access Token 进入响应体，Refresh Token 仅写入 HttpOnly Cookie。</p>
 */
@Tag(name = "身份认证")
@RestController
@RequestMapping("/fd/v1/auth")
public class AuthController {

    private final AuthLoginService authLoginService;
    private final AuthProperties authProperties;

    public AuthController(AuthLoginService authLoginService, AuthProperties authProperties) {
        this.authLoginService = authLoginService;
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<R<AuthLoginBO>> login(@Valid @RequestBody AuthVO authVO) {
        AuthLoginResultBO result = authLoginService.login(authVO);

        // 构建 Refresh Token 的 Cookie
        ResponseCookie refreshCookie = ResponseCookie
                .from(authProperties.getRefreshCookieName(), result.refreshToken())
                .httpOnly(true)
                .secure(authProperties.isCookieSecure())
                .sameSite("Strict")
                .path(authProperties.getRefreshCookiePath())
                .maxAge(authProperties.getRefreshExpiration())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(R.success(result.response()));
    }
}
