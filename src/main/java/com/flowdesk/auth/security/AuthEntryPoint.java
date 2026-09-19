package com.flowdesk.auth.security;

import com.flowdesk.common.exception.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 认证失败的统一出口：401 的编码与文案只在这里定义一次，安全链与请求认证过滤器都经本类输出。
 *
 * <p>两种已确认的原因对应两个错误编码：没有凭据（或凭据不可验证）是 {@code AUTH_REQUIRED}，
 * 凭据本身有效但它指向的会话已不存在是 {@code AUTH_SESSION_INVALID}。</p>
 */
@Component
public class AuthEntryPoint implements AuthenticationEntryPoint {

    /** 请求认证过滤器发现会话失效时写入的请求属性，供本类区分错误编码。 */
    public static final String SESSION_INVALID_ATTRIBUTE = AuthEntryPoint.class.getName() + ".SESSION_INVALID";

    private final ApiErrorWriter errorWriter;

    public AuthEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /** 安全链在未认证请求被授权规则拦下时调用。 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute(SESSION_INVALID_ATTRIBUTE))) {
            sessionInvalid(response);
            return;
        }
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "未提供或无法验证 Access Token");
    }

    /**
     * 凭据本身有效、但它指向的会话已不存在。
     *
     * <p>这种情况不能与"没有凭据"合并成一个编码：客户端要能区分"需要重新登录"和"会话已被结束"。
     * 它只在受保护路径被授权规则拦下时生效；匿名路径不会走到这里，因此刷新与退出可以照常处理。</p>
     */
    private void sessionInvalid(HttpServletResponse response) throws IOException {
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "AUTH_SESSION_INVALID", "登录会话已失效，请重新登录");
    }
}