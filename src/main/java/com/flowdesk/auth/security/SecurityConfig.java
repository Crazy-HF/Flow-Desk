package com.flowdesk.auth.security;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.service.TokenService;
import com.flowdesk.shared.exception.ApiErrorWriter;
import com.flowdesk.shared.utils.JwtProperties;
import com.flowdesk.shared.utils.JwtUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtUtils jwtUtils,
            TokenService tokenService,
            ApiErrorWriter errorWriter
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/fd/v1/auth/login",
                                "/fd/v1/auth/refresh").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(response, HttpStatus.UNAUTHORIZED,
                                        "AUTH_REQUIRED", "需要有效的登录身份"))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(response, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "当前身份无权执行该操作")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(sessionAwareDecoder(jwtUtils, tokenService))
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            claimValues(jwt.getClaimAsStringList("roles")).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
            claimValues(jwt.getClaimAsStringList("permissions")).stream()
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
            return authorities;
        });
        return converter;
    }

    private List<String> claimValues(List<String> values) {
        return values == null ? List.of() : values;
    }

    private JwtDecoder sessionAwareDecoder(JwtUtils jwtUtils, TokenService tokenService) {
        return token -> {
            Jwt jwt = jwtUtils.parseAndValidate(token);
            String sessionId = jwtUtils.getSessionId(jwt);
            Long userId;
            try {
                userId = Long.valueOf(jwt.getSubject());
            } catch (RuntimeException exception) {
                throw invalidSession();
            }
            AuthSession session = tokenService.findActiveSession(sessionId, userId)
                    .orElseThrow(this::invalidSession);
            Map<String, Object> claims = new LinkedHashMap<>(jwt.getClaims());
            claims.put("roles", session.roles());
            claims.put("permissions", session.permissions());
            return new Jwt(
                    jwt.getTokenValue(),
                    jwt.getIssuedAt(),
                    jwt.getExpiresAt(),
                    jwt.getHeaders(),
                    claims
            );
        };
    }

    private JwtValidationException invalidSession() {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "登录会话已过期或被撤销", null);
        return new JwtValidationException("登录会话无效", List.of(error));
    }
}
