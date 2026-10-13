package com.flowdesk.auth.infrastructure;

import com.flowdesk.attachment.application.port.CurrentUploaderPort;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 通用附件模块的当前上传者出口实现：从认证后的 {@link AuthPrincipal} 读取身份。
 *
 * <p>与 {@code TicketCurrentRequesterAdapter} 同款——每个被依赖模块各自持有自己的出口，
 * 附件模块不需要 import 认证域类型。</p>
 */
@Component
public class AttachmentCurrentUploaderAdapter implements CurrentUploaderPort {

    @Override
    public long currentUserId() {
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