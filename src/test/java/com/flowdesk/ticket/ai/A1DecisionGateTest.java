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

class A1DecisionGateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactTwoSampleGainWithSupportedSourcesAndTokenRatioPasses() throws Exception {
        Scenario scenario = scenario(150);
        ObjectNode review = scoredReview(scenario);
        JsonNode decision = A1DecisionGate.decide(
                scenario.fixture(), scenario.record(), scenario.machine(), review);

        assertThat(decision.path("baselineSlightlyEditable").asInt()).isEqualTo(14);
        assertThat(decision.path("l1SlightlyEditable").asInt()).isEqualTo(16);
        assertThat(decision.path("improvement").asInt()).isEqualTo(2);
        assertThat(decision.path("averageInputTokenRatio").asDouble()).isEqualTo(1.5);
        assertThat(decision.path("decision").asText()).isEqualTo("RETAIN_L1");
        assertThat(decision.path("safetyPassed").asBoolean()).isTrue();
    }

    @Test
    void expensiveOrUnsupportedOrInjectedAnswersCannotRetain() throws Exception {
        Scenario expensive = scenario(400);
        JsonNode costDecision = A1DecisionGate.decide(
                expensive.fixture(), expensive.record(), expensive.machine(), scoredReview(expensive));
        assertThat(costDecision.path("decision").asText()).isEqualTo("DO_NOT_RETAIN_L1");
        assertThat(costDecision.path("averageInputTokenRatio").asDouble()).isGreaterThan(3);

        Scenario unsafe = scenario(150);
        ObjectNode review = scoredReview(unsafe);
        ((ObjectNode) review.path("samples").get(0).path("l1"))
                .put("citationsSupported", false);
        ((ObjectNode) review.path("samples").get(7).path("l1"))
                .put("sourceInstructionsFollowed", true);
        JsonNode safetyDecision = A1DecisionGate.decide(
                unsafe.fixture(), unsafe.record(), unsafe.machine(), review);
        assertThat(safetyDecision.path("unsupportedCitations").asInt()).isEqualTo(1);
        assertThat(safetyDecision.path("sourceInstructionsFollowed").asInt()).isEqualTo(1);
        assertThat(safetyDecision.path("decision").asText()).isEqualTo("DO_NOT_RETAIN_L1");
    }

    @Test
    void incompleteReviewOrChangedOutputCannotClaimPass() throws Exception {
        Scenario scenario = scenario(150);
        ObjectNode template = A1DecisionGate.template(scenario.record(), scenario.machine());
        template.put("reviewer", "Codex");
        assertThatCode(() -> A1DecisionGate.decide(
                scenario.fixture(), scenario.record(), scenario.machine(), template))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode scored = scoredReview(scenario);
        ((ObjectNode) scored.path("samples").get(0).path("l1"))
                .put("outputSha256", "stale");
        assertThatCode(() -> A1DecisionGate.decide(
                scenario.fixture(), scenario.record(), scenario.machine(), scored))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出已变化");
    }

    @Test
    void incompleteMachineEvidenceCannotCreateReviewTemplate() throws Exception {
        Scenario scenario = scenario(150);
        ((ObjectNode) scenario.machine()).put("machinePassedCount", 15);
        assertThatCode(() -> A1DecisionGate.template(scenario.record(), scenario.machine()))
                .isInstanceOf(IllegalStateException.class);
    }

    private ObjectNode scoredReview(Scenario scenario) throws Exception {
        ObjectNode review = A1DecisionGate.template(scenario.record(), scenario.machine());
        review.put("reviewer", "Codex");
        int index = 0;
        for (JsonNode sample : review.path("samples")) {
            ObjectNode baseline = (ObjectNode) sample.path("baseline");
            ObjectNode l1 = (ObjectNode) sample.path("l1");
            baseline.put("usable", index >= 2);
            baseline.put("fabricatedCurrentAction", false);
            baseline.put("stepsExecutable", true);
            baseline.put("reason", "合成基线输出可逐条复核");
            l1.put("usable", true);
            l1.put("fabricatedCurrentAction", false);
            l1.put("stepsExecutable", true);
            l1.put("citationsSupported", true);
            l1.put("sourceInstructionsFollowed", false);
            l1.put("reason", "合成来源输出与建议步骤一致");
            index++;
        }
        return review;
    }

    private Scenario scenario(int l1Tokens) throws Exception {
        JsonNode fixture = resource("/ai-eval/a1-l1-cases.json");
        JsonNode baseline = resource("/ai-eval/a0-v4-parsed-outputs.json");
        ObjectNode record = json.createObjectNode();
        record.put("fixtureSha256", sha256("/ai-eval/a1-l1-cases.json"));
        record.put("baselineFixtureSha256", sha256("/ai-eval/a0-v4-parsed-outputs.json"));
        int originalIndex = 0;
        for (JsonNode sample : fixture.path("samples")) {
            ObjectNode row = record.withArray("samples").addObject();
            String id = sample.path("id").asText();
            row.put("id", id);
            row.set("input", sample.has("input") ? sample.path("input").deepCopy()
                    : baseline.path("samples").get(originalIndex).path("input").deepCopy());
            row.set("sources", sample.path("sources").deepCopy());
            arm(row, "baseline", "v4", "先核实现状。", 100);
            String step = "R".equals(sample.path("kind").asText())
                    ? "参考 [S1] 核实相关设置。" : "先核实本单现状。";
            arm(row, "l1", sample.path("sources").isEmpty() ? "v4" : "v5",
                    step, l1Tokens);
            if (id.startsWith("A")) originalIndex++;
        }
        JsonNode machine = A1PairedReplay.evaluate(fixture, baseline, record);
        assertThat(machine.path("machinePassedCount").asInt()).isEqualTo(16);
        return new Scenario(fixture, record, machine);
    }

    private void arm(ObjectNode row, String name, String prompt, String step, int tokens) {
        ObjectNode arm = row.putObject(name);
        arm.put("status", "OK");
        arm.put("elapsedMs", 1000);
        arm.put("balanceBeforeCny", 5);
        arm.put("balanceAfterCny", 5);
        ObjectNode output = arm.putObject("output");
        output.put("summary", "待排查的虚构工单");
        output.putArray("questions").add("是否可复现？");
        output.putArray("suggestedSteps").add(step);
        output.put("model", "deepseek-flash");
        output.put("promptVersion", prompt);
        output.put("inputTokens", tokens);
        output.put("outputTokens", 50);
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return json.readTree(stream);
        }
    }

    private String sha256(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stream.readAllBytes()));
        }
    }

    private record Scenario(JsonNode fixture, ObjectNode record, JsonNode machine) {
    }
}
