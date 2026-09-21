package com.flowdesk.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 仅审计方法、路径、状态和耗时。故意不读取请求头、查询参数、Cookie 或请求体，
 * 因而不会把密码、令牌或附件内容写入日志。
 *
 * <p>开始行为 DEBUG、结束行为 INFO：一次请求的耗时与结果是运维事实，任何环境都值得记录；
 * 开始行只是开发时用来把一次请求在控制台里框出来，不构成生产的默认成本。
 * 把开始行开出来的方式见 {@code application-local.yml}。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestAuditFilter extends OncePerRequestFilter {

    /** 开始行的固定前缀，便于日志检索与用例断言。 */
    public static final String START_MESSAGE = "request start";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("{} method={} path={}", START_MESSAGE, request.getMethod(), request.getRequestURI());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("request end method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMillis);
        }
    }
}
