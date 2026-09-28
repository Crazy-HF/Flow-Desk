package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.stereotype.Component;

@Component
public class TicketCurrentRequesterAdapter implements CurrentRequesterPort {

    /**从认证后的 AuthPrincipal 读取身份*/
    @Override
    public long currentUserId() {
        //从当前上下文中获取用户ID
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                instanceof AuthPrincipal principal)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTH_REQUIRED",
                    "未提供或无法验证 Access Token");
        }

        return principal.userId();
    }
}
