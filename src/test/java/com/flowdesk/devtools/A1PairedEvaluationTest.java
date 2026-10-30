package com.flowdesk.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

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
    void failureDetailOnlyRecordsKnownParserCategories() {
        var known = new TicketAiModelPort.ModelFailure(
                TicketAiModelPort.FailureKind.INVALID_OUTPUT,
                new IllegalStateException("FINISH_REASON_LENGTH"));
        var unknown = new TicketAiModelPort.ModelFailure(
                TicketAiModelPort.FailureKind.INVALID_OUTPUT,
                new IllegalStateException("supplier body: secret"));
        var malformed = new TicketAiModelPort.ModelFailure(
                TicketAiModelPort.FailureKind.INVALID_OUTPUT,
                new com.fasterxml.jackson.core.JsonParseException(null, "raw supplier content"));

        assertThat(A1PairedEvaluation.failureDetail(known)).isEqualTo("FINISH_REASON_LENGTH");
        assertThat(A1PairedEvaluation.failureDetail(malformed)).isEqualTo("MALFORMED_JSON");
        assertThat(A1PairedEvaluation.failureDetail(unknown)).isEqualTo("UNKNOWN");
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

    @Test
    void everyFixedCaseFeedsTheRecorderWithTheSameCurrentTicketOnBothArms() throws Exception {
        JsonNode a0 = resource("/ai-eval/a0-v4-parsed-outputs.json");
        JsonNode a1 = resource("/ai-eval/a1-l1-cases.json");
        int checked = 0;
        for (JsonNode sample : a1.path("samples")) {
            String id = sample.path("id").asText();
            JsonNode input = sample.has("input") ? sample.path("input") : a0Input(a0, id);
            JsonNode sources = sample.path("sources");
            TicketAiModelPort.Input baseline = A1PairedEvaluation.modelInput(input, sources, "baseline");
            TicketAiModelPort.Input l1 = A1PairedEvaluation.modelInput(input, sources, "l1");

            // 配对比较只允许来源不同；旧十例必须复用 A0 的原始五项输入。
            assertThat(baseline.title()).as(id).isEqualTo(input.path("title").asText());
            assertThat(baseline.description()).as(id).isEqualTo(input.path("description").asText());
            assertThat(baseline.category()).as(id).isEqualTo(input.path("category").asText());
            assertThat(baseline.priority()).as(id).isEqualTo(input.path("priority").asText());
            assertThat(baseline.status()).as(id).isEqualTo(input.path("status").asText());
            assertThat(baseline.sources()).as(id).isEmpty();
            assertThat(l1.title()).as(id).isEqualTo(baseline.title());
            assertThat(l1.description()).as(id).isEqualTo(baseline.description());
            assertThat(l1.category()).as(id).isEqualTo(baseline.category());
            assertThat(l1.priority()).as(id).isEqualTo(baseline.priority());
            assertThat(l1.status()).as(id).isEqualTo(baseline.status());
            assertThat(l1.sources()).as(id).hasSize(sources.size());
            for (int index = 0; index < sources.size(); index++) {
                JsonNode source = sources.get(index);
                assertThat(l1.sources().get(index)).as(id + " source " + index)
                        .isEqualTo(new TicketAiModelPort.Source(
                                source.path("label").asText(), source.path("title").asText(),
                                source.path("processExcerpt").asText()));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(16);
    }

    private JsonNode a0Input(JsonNode a0, String id) {
        for (JsonNode sample : a0.path("samples")) {
            if (id.equals(sample.path("id").asText())) return sample.path("input");
        }
        throw new IllegalArgumentException("A0 中缺少配对样本 " + id);
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).as(name).isNotNull();
            return json.readTree(stream);
        }
    }
}
