package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.common.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 当前操作人端口的 adapter 测试：{@code granted_by} 只能来自真实认证身份。
 *
 * <p>IAM 侧不引用 auth 模块，端口实现放在 auth 的 infrastructure 下，因此这里直接断言
 * {@code SecurityContext} 到 {@code userId} 的取值规则与匿名/非本项目身份时的失败口径。</p>
 */
class IamCurrentOperatorAdapterTest {

    private static final long USER_ID = 3L;

    private final IamCurrentOperatorAdapter adapter = new IamCurrentOperatorAdapter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsUserIdOfAuthenticatedProjectPrincipal() {
        authenticate(new AuthPrincipal(USER_ID, "admin", "session-1"));

        assertThat(adapter.currentUserId()).isEqualTo(USER_ID);
    }

    @Test
    void rejectsMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertAuthRequired();
    }

    @Test
    void rejectsUnauthenticatedToken() {
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken
                .unauthenticated(
                        new AuthPrincipal(USER_ID, "admin", "session-1"),
                        null);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertAuthRequired();
    }

    /** 其他认证方式（例如测试替身或匿名令牌）不能伪造操作人身份。 */
    @Test
    void rejectsPrincipalThatIsNotProjectIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "anonymousUser", null, AuthorityUtils.NO_AUTHORITIES));

        assertAuthRequired();
    }

    private static void authenticate(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
    }

    private void assertAuthRequired() {
        assertThatThrownBy(adapter::currentUserId)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.code()).isEqualTo("AUTH_REQUIRED");
                });
    }
}
