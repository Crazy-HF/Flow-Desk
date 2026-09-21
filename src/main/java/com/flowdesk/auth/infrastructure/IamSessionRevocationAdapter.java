package com.flowdesk.auth.infrastructure;

import com.flowdesk.iam.service.SessionRevocationPort;
import org.springframework.stereotype.Component;

/**
 * IAM 发起的会话撤销出口适配器。
 */
@Component
public class IamSessionRevocationAdapter implements SessionRevocationPort {

    /**认证会话仓储。*/
    private final AuthSessionRepository authSessionRepository;

    public IamSessionRevocationAdapter(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    /**
     * 撤销指定用户的全部登录会话。
     *
     * @param userId 用户 ID
     */
    @Override
    public void revokeAll(long userId) {
        authSessionRepository.revokeAll(userId);
    }
}