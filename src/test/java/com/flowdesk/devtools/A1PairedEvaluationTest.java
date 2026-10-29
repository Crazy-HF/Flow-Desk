package com.flowdesk.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class A1PairedEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void a1RequiresItsOwnExplicitPaidSwitch() {
        assertThatCode(() -> A1PairedEvaluation.requireA1Authorization(
                "YES", "YES", null, "true", "test-key", "1.00"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> A1PairedEvaluation.requireA1Authorization(
                "YES", "YES", "YES", "true", "test-key", "1.00"))
                .doesNotThrowAnyException();
    }

    @Test
    void pairedInputsKeepFiveFieldsIdenticalAndOnlyL1CarriesSources() throws Exception {
        var input = json.readTree("""
                {"title":"虚构故障","description":"现象待核实","category":"办公设备",
                 "priority":"LOW","status":"PROCESSING"}
                """);
        var sources = json.readTree("""
                [{"label":"[S1]","title":"虚构来源","processExcerpt":"先检查连接"}]
                """);
        TicketAiModelPort.Input baseline = A1PairedEvaluation.modelInput(input, sources, "baseline");
        TicketAiModelPort.Input l1 = A1PairedEvaluation.modelInput(input, sources, "l1");

        assertThat(baseline.sources()).isEmpty();
        assertThat(l1.sources()).containsExactly(
                new TicketAiModelPort.Source("[S1]", "虚构来源", "先检查连接"));
        assertThat(l1.title()).isEqualTo(baseline.title());
        assertThat(l1.description()).isEqualTo(baseline.description());
        assertThat(l1.category()).isEqualTo(baseline.category());
        assertThat(l1.priority()).isEqualTo(baseline.priority());
        assertThat(l1.status()).isEqualTo(baseline.status());
    }
}
