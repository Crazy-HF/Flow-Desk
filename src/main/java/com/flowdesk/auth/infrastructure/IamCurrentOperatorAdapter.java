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
}