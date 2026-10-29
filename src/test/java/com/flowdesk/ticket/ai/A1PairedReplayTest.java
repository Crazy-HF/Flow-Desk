package com.flowdesk.ticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class A1PairedReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void offlineCheckReportsPartialPairsAndNeverAutoApprovesQuality() throws Exception {
        JsonNode fixture = resource("/ai-eval/a1-l1-cases.json");
        JsonNode baseline = resource("/ai-eval/a0-v4-parsed-outputs.json");
        ObjectNode record = record(fixture, baseline);
        ObjectNode item = record.withArray("samples").addObject();
        JsonNode sample = fixture.path("samples").get(0);
        item.put("id", "A01");
        item.set("input", baseline.path("samples").get(0).path("input").deepCopy());
        item.set("sources", sample.path("sources").deepCopy());
        arm(item, "baseline", "v4", "先核实当前输入源。", 100);
        arm(item, "l1", "v5", "参考 [S1] 核实输入源。", 150);

        JsonNode report = A1PairedReplay.evaluate(fixture, baseline, record);
        assertThat(report.path("pairedCount").asInt()).isEqualTo(1);
        assertThat(report.path("machinePassedCount").asInt()).isEqualTo(1);
        assertThat(report.path("averageInputTokenRatio").asDouble()).isEqualTo(1.5);
        assertThat(report.path("humanReviewPending").asBoolean()).isTrue();
        assertThat(report.path("gateA1Passed").asBoolean()).isFalse();
    }

    @Test
    void unknownLabelsBareNumbersAndAlteredInputsAreMachineFailures() throws Exception {
        JsonNode fixture = resource("/ai-eval/a1-l1-cases.json");
        JsonNode baseline = resource("/ai-eval/a0-v4-parsed-outputs.json");
        ObjectNode record = record(fixture, baseline);
        ObjectNode item = record.withArray("samples").addObject();
        item.put("id", "A01");
        item.set("input", baseline.path("samples").get(0).path("input").deepCopy());
        item.set("sources", fixture.path("samples").get(0).path("sources").deepCopy());
        arm(item, "baseline", "v4", "核实设备。", 100);
        arm(item, "l1", "v5", "参考 [S7]，查看 FD-20261029-001。", 150);

        JsonNode errors = A1PairedReplay.evaluate(fixture, baseline, record)
                .path("samples").get(0).path("machineErrors");
        assertThat(errors.toString()).contains("L1_UNKNOWN_LABEL", "L1_BARE_TICKET_NO");

        ((ObjectNode) item.path("input")).put("title", "被改写");
        errors = A1PairedReplay.evaluate(fixture, baseline, record)
                .path("samples").get(0).path("machineErrors");
        assertThat(errors.toString()).contains("FIXTURE_INPUT_MISMATCH");
    }

    @Test
    void alteredFixtureHashStopsReplay() throws Exception {
        JsonNode fixture = resource("/ai-eval/a1-l1-cases.json");
        JsonNode baseline = resource("/ai-eval/a0-v4-parsed-outputs.json");
        ObjectNode record = record(fixture, baseline);
        record.put("fixtureSha256", "wrong");
        assertThatCode(() -> A1PairedReplay.evaluate(fixture, baseline, record))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void arm(ObjectNode item, String name, String prompt, String step, int tokens) {
        ObjectNode arm = item.putObject(name);
        arm.put("status", "OK");
        arm.put("elapsedMs", 1000);
        arm.put("balanceBeforeCny", 5);
        arm.put("balanceAfterCny", 5);
        ObjectNode output = arm.putObject("output");
        output.put("summary", "需要排查");
        output.putArray("questions").add("现象是否持续？");
        output.putArray("suggestedSteps").add(step);
        output.put("model", "deepseek-flash");
        output.put("promptVersion", prompt);
        output.put("inputTokens", tokens);
        output.put("outputTokens", 50);
    }

    private ObjectNode record(JsonNode fixture, JsonNode baseline) throws Exception {
        ObjectNode result = json.createObjectNode();
        result.put("fixtureSha256", sha256("/ai-eval/a1-l1-cases.json"));
        result.put("baselineFixtureSha256", sha256("/ai-eval/a0-v4-parsed-outputs.json"));
        result.putArray("samples");
        return result;
    }

    private String sha256(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stream.readAllBytes()));
        }
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return json.readTree(stream);
        }
    }
}
