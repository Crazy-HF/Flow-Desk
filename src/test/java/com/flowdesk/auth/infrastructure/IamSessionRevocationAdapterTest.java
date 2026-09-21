package com.flowdesk.auth.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class IamSessionRevocationAdapterTest {

    private static final long USER_ID = 42L;

    private final AuthSessionRepository repository =
            mock(AuthSessionRepository.class);

    private final IamSessionRevocationAdapter adapter =
            new IamSessionRevocationAdapter(repository);


    @Test
    void delegatesRevokeAllToAuthSessionRepository() {
        adapter.revokeAll(USER_ID);

        verify(repository).revokeAll(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void propagatesRepositoryFailure() {
        RuntimeException failure = new RuntimeException("failure");

        doThrow(failure).when(repository).revokeAll(USER_ID);
        assertThatThrownBy(() -> adapter.revokeAll(USER_ID))
                .isSameAs(failure);
        verify(repository).revokeAll(USER_ID);
        verifyNoMoreInteractions(repository);
    }
}