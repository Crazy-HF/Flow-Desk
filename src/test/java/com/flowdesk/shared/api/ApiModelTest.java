package com.flowdesk.shared.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ApiModelTest {

    @Test
    void responseEnvelopeUsesStableSuccessAndFailureCodes() {
        assertThat(R.success("ticket")).isEqualTo(new R<>("SUCCESS", "操作成功", "ticket"));
        assertThat(R.failure("TICKET_CONFLICT", "状态已变化", null))
                .isEqualTo(new R<>("TICKET_CONFLICT", "状态已变化", null));
    }

    @Test
    void pageResultIsOneBasedAndMakesItemsImmutable() {
        List<String> source = new ArrayList<>(List.of("FD-2026-000001"));
        PageResult<String> result = new PageResult<>(source, 1, 20, 1, 1);

        source.add("FD-2026-000002");

        assertThat(result.items()).containsExactly("FD-2026-000001");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageResult<>(List.of(), 0, 20, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageResult<>(List.of(), 1, 0, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageResult<>(List.of(), 1, 20, -1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageResult<>(List.of(), 1, 20, 0, -1));
    }
}
