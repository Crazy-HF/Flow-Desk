package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IamCurrentOperatorAdapter implements CurrentOperatorPort {

    @Override
    public long currentUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTH_REQUIRED",
                    "未提供或无法验证 Access Token"
            );
        }

        return principal.userId();
    }

    /**
     * 当前请求是否持有某个业务权限。
     *
     * <p>与 {@code @PreAuthorize} 同源：都读认证对象里的授权集合，不另查数据库。
     * 未认证或不是本系统的认证主体时返回 {@code false}，由调用方决定是 403 还是别的处置。</p>
     */
    @Override
    public boolean hasAuthority(String authority) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authority == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}