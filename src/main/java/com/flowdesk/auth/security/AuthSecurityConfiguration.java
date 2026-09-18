package com.flowdesk.auth.security;

import com.flowdesk.common.exception.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 认证模块在公共安全基线上叠加的过滤链：只接住 {@code /fd/v1/auth/**}，并声明认证路径的放行规则。
 *
 * <p>{@code @Order(1)} 让它先于基础链匹配——两条链都命中时 {@code FilterChainProxy} 只取第一条。
 * 本链必须自行关闭 CSRF：每条链由各自的 {@code HttpSecurity} 构建，基础链里的关闭配置不会继承过来。</p>
 */
@Configuration
@Order(1)
public class AuthSecurityConfiguration {

    @Bean
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
        http
                .securityMatcher("/fd/v1/auth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/fd/v1/auth/login").permitAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, failure) ->
                        errorWriter.write(response, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED",
                                "未提供或无法验证 Access Token")));
        return http.build();
    }
}
