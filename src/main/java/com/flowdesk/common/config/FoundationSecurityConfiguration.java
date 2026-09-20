package com.flowdesk.common.config;

import com.flowdesk.common.exception.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 基础安全配置。
 *
 * <p>只提供与业务模块无关的安全基线：纯 JSON API 不使用 CSRF 和表单登录，会话保持无状态，
 * 未认证请求统一按 {@code R} 错误信封返回 {@code AUTH_REQUIRED}。认证相关的过滤器
 * （JWT 解析、Redis 会话校验）与认证路径的匿名放行规则由 auth 模块在此基础上叠加。</p>
 *
 * <p>这条链放在 common 而不是 auth，是为了让公共契约不随业务模块的重建而失效。</p>
 */
@Configuration
@EnableMethodSecurity
public class FoundationSecurityConfiguration {

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, failure) ->
                        errorWriter.write(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "AUTH_REQUIRED",
                                "未提供或无法验证 Access Token")));
        return http.build();
    }
}
