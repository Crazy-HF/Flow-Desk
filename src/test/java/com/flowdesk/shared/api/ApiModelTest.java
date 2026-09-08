package com.flowdesk.shared.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import com.flowdesk.shared.web.R;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ApiModelTest {

    @Test
    void responseEnvelopeUsesStableSuccessAndFailureCodes() {
        assertThat(R.success("ticket")).isEqualTo(new R<>("OK", "操作成功", "ticket"));
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

    @Test
    void pageQueryRejectsInvalidValuesInsteadOfCorrectingThem() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        PageQuery query = new PageQuery();
        query.setPageNo(0);
        query.setPageSize(101);

        assertThat(validator.validate(query))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("pageNo", "pageSize");
        assertThat(query.getCurrent()).isZero();
        assertThat(query.getSize()).isEqualTo(101);
    }

    @Test
    void pageResultConvertsMybatisPlusPageAndItsRecords() {
        Page<String> source = new Page<>(2, 20, 45);
        source.setRecords(List.of("user-1", "user-2"));

        PageResult<Integer> result = PageResult.from(source, String::length);

        assertThat(result.items()).containsExactly(6, 6);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(45);
        assertThat(result.totalPages()).isEqualTo(3);
    }
}
