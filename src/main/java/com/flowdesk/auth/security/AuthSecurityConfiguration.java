package com.flowdesk.auth.security;

import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * 认证模块在公共安全基线上叠加的过滤链：只接住 {@code /fd/v1/auth/**}，并声明认证路径的放行规则。
 *
 * <p>{@code @Order(1)} 让它先于基础链匹配——两条链都命中时 {@code FilterChainProxy} 只取第一条。
 * 本链必须自行关闭 CSRF：每条链由各自的 {@code HttpSecurity} 构建，基础链里的关闭配置不会继承过来。</p>
 *
 * <p>授权规则必须显式写出：没有匹配规则的请求不会被任何规则拒绝，匿名接口只能靠
 * {@code permitAll} 显式放行，其余一律要求已认证。</p>
 */
@Configuration
@Order(1)
public class AuthSecurityConfiguration {

    @Bean
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http,
                                                JwtTokenService jwtTokenService,
                                                AuthSessionRepository authSessionRepository,
                                                AuthEntryPoint authEntryPoint,
                                                Clock clock) throws Exception {
        // 直接构造而不声明为 Bean：Filter Bean 会被 Boot 另行注册到 Servlet 链，导致认证失效
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtTokenService, authSessionRepository, clock);

        http
                .securityMatcher("/fd/v1/auth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/fd/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/fd/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/fd/v1/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(authEntryPoint));
        return http.build();
    }
}