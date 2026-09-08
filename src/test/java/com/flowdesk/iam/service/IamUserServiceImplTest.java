package com.flowdesk.iam.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.domain.bo.IamUserBO;
import com.flowdesk.iam.domain.vo.IamUserCreateVO;
import com.flowdesk.iam.domain.vo.IamUserResetPasswordVO;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.service.impl.IamUserServiceImpl;
import com.flowdesk.shared.exception.ApiException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamUserServiceImplTest {

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IamUser.class
        );
    }

    @Test
    void createUserHashesPasswordBeforeInsertAndReturnsSafeBo() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T08:00:00Z"), ZoneOffset.UTC);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, clock);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        when(passwordEncoder.encode("initial-password")).thenReturn("{argon2}$argon2id$encoded");
        when(mapper.insert(any(IamUser.class))).thenAnswer(invocation -> {
            IamUser user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });

        IamUserCreateVO request = new IamUserCreateVO();
        request.setUsername("  alice  ");
        request.setDisplayName("  Alice  ");
        request.setPassword("initial-password");

        IamUserBO result = service.createIamUser(request);

        verify(passwordEncoder).encode("initial-password");
        ArgumentCaptor<IamUser> userCaptor = ArgumentCaptor.forClass(IamUser.class);
        verify(mapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("{argon2}$argon2id$encoded");
        assertThat(userCaptor.getValue().getPasswordHash()).doesNotContain("initial-password");
        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.getDisplayName()).isEqualTo("Alice");
        assertThat(result.getStatus()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(result.getVersion()).isZero();
        assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-09-08T08:00:00"));
        assertThat(result.getUpdatedAt()).isEqualTo(result.getCreatedAt());
    }

    @Test
    void disableUserChangesStatusAndUpdateTime() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T09:30:00Z"), ZoneOffset.UTC);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, clock);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        IamUser user = new IamUser();
        user.setId(42L);
        user.setStatus(IamUserStatus.ENABLED);
        user.setVersion(0L);
        when(mapper.selectById(42L)).thenReturn(user);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.disableIamUser(42L, 0L);

        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void disableUserIsIdempotentWhenAlreadyDisabled() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        IamUserServiceImpl service = new IamUserServiceImpl(
                mock(PasswordEncoder.class), Clock.systemUTC());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        IamUser user = new IamUser();
        user.setId(42L);
        user.setStatus(IamUserStatus.DISABLED);
        when(mapper.selectById(42L)).thenReturn(user);

        service.disableIamUser(42L, 0L);

        verify(mapper, never()).update(any(), any());
    }

    @Test
    void disableUserRejectsMissingUser() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        IamUserServiceImpl service = new IamUserServiceImpl(
                mock(PasswordEncoder.class), Clock.systemUTC());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.disableIamUser(99L, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void resetPasswordHashesPasswordAndUsesOptimisticVersion() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, clock);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        IamUser user = new IamUser();
        user.setId(42L);
        user.setVersion(3L);
        when(mapper.selectById(42L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("{argon2}$argon2id$new-hash");
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        IamUserResetPasswordVO request = resetPasswordRequest("new-password", 3L);
        service.resetIamUserPassword(42L, request);

        verify(passwordEncoder).encode("new-password");
        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void resetPasswordRejectsMissingUserBeforeHashing() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, Clock.systemUTC());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.resetIamUserPassword(
                99L, resetPasswordRequest("new-password", 0L)))
                .isInstanceOf(ApiException.class)
                .hasMessage("用户不存在");

        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void resetPasswordRejectsStaleVersionBeforeHashing() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, Clock.systemUTC());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        IamUser user = new IamUser();
        user.setId(42L);
        user.setVersion(4L);
        when(mapper.selectById(42L)).thenReturn(user);

        assertThatThrownBy(() -> service.resetIamUserPassword(
                42L, resetPasswordRequest("new-password", 3L)))
                .isInstanceOf(ApiException.class)
                .hasMessage("用户信息或版本已发生变化");

        verify(passwordEncoder, never()).encode(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void resetPasswordReportsConflictWhenConditionalUpdateLosesRace() {
        IamUserMapper mapper = mock(IamUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        IamUserServiceImpl service = new IamUserServiceImpl(passwordEncoder, Clock.systemUTC());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        IamUser user = new IamUser();
        user.setId(42L);
        user.setVersion(3L);
        when(mapper.selectById(42L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("{argon2}$argon2id$new-hash");
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.resetIamUserPassword(
                42L, resetPasswordRequest("new-password", 3L)))
                .isInstanceOf(ApiException.class)
                .hasMessage("用户信息或版本已发生变化");
    }

    private IamUserResetPasswordVO resetPasswordRequest(String newPassword, Long version) {
        IamUserResetPasswordVO request = new IamUserResetPasswordVO();
        request.setNewPassword(newPassword);
        request.setVersion(version);
        return request;
    }
}
