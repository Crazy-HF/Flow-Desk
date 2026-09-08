package com.flowdesk.shared.config;

import com.flowdesk.shared.exception.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 基础安全配置
 */
@Configuration
@EnableMethodSecurity
public class FoundationSecurityConfiguration {

    @Bean
    UserDetailsService foundationUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                                        "AUTH_REQUIRED", "需要有效的登录身份"))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(response, org.springframework.http.HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "当前身份无权执行该操作")))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout.disable())
                // API 使用令牌认证；浏览器表单 CSRF 机制不适用于此公共 API 契约。
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
