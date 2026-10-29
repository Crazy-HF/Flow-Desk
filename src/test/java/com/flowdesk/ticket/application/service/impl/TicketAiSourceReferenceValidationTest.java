package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 在模型替身输出上验证来源引用闸门；不连接数据库或供应商。 */
class TicketAiSourceReferenceValidationTest {

    private final List<?> sources = List.of(source("FD-20261020-001", "[S1]"));

    @Test
    void acceptsOnlyAssignedCanonicalLabels() {
        var answer = output("可参考 [S1] 的排查顺序。");

        assertThat(validate(answer)).isSameAs(answer);
    }

    @Test
    void rejectsUnknownAndMalformedLabelsInEveryOutputField() {
        for (String label : List.of("[S2]", "[S 1]", "[s1]", "[S1", "[Sx]")) {
            assertInvalid(output("参考 " + label));
        }
        assertInvalid(new TicketAiModelPort.Output("摘要", List.of("参考 [S7]"),
                List.of("继续核实"), "deepseek-flash", "v5", null, null));
        assertInvalid(new TicketAiModelPort.Output("摘要", List.of("继续核实"),
                List.of("参考 [S7]"), "deepseek-flash", "v5", null, null));
    }

    @Test
    void rejectsKnownAndOtherBareTicketNumbers() {
        assertInvalid(output("查看 FD-20261020-001"));
        assertInvalid(output("查看 FD-20261021-999999"));
    }

    private void assertInvalid(TicketAiModelPort.Output answer) {
        var failure = catchThrowableOfType(() -> validate(answer),
                TicketAiModelPort.ModelFailure.class);
        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(TicketAiModelPort.FailureKind.INVALID_OUTPUT);
    }

    private TicketAiModelPort.Output validate(TicketAiModelPort.Output answer) {
        return ReflectionTestUtils.invokeMethod(TicketAiSuggestionServiceImpl.class,
                "validateSourceReferences", answer, sources);
    }

    private static TicketAiModelPort.Output output(String summary) {
        return new TicketAiModelPort.Output(summary, List.of("继续核实"),
                List.of("检查设备"), "deepseek-flash", "v5", null, null);
    }

    private static Object source(String ticketNo, String label) {
        try {
            Class<?> type = Class.forName(TicketAiSuggestionServiceImpl.class.getName()
                    + "$PreviewSource");
            Constructor<?> constructor = type.getDeclaredConstructor(long.class, String.class,
                    String.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(1L, ticketNo, label, "虚构来源", "核实设备连接");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法构造服务层来源测试数据", exception);
        }
    }
}
