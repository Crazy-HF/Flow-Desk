package com.flowdesk.ticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsedV4FixtureReplaysTenSamplesWithSeparateHumanRatings() throws Exception {
        JsonNode fixture = resource("/ai-eval/a0-v4-parsed-outputs.json");
        JsonNode ratings = resource("/ai-eval/a0-v4-ratings.json");

        ObjectNode report = AiEvaluationReplay.evaluate(fixture, ratings, mapper);
        report.put("fixtureSha256", fixtureDigest());

        assertThat(report.path("sampleCount").asInt()).isEqualTo(10);
        assertThat(report.path("structurePassed").asInt()).isEqualTo(10);
        assertThat(report.path("reviewedFabricatedActions").asInt()).isZero();
        assertThat(report.path("slightlyEditable").asInt()).isEqualTo(9);
        assertThat(report.path("evidenceKind").asText()).isEqualTo("PARSED_OUTPUT_ONLY");
        assertThat(report.path("samples").size()).isEqualTo(10);
        assertThat(report.path("samples").get(7).path("usable").asBoolean()).isFalse();
        Path directory = Path.of("target", "ai-eval");
        AiEvaluationReplay.writeReport(report, directory, mapper);
        byte[] firstJson = Files.readAllBytes(directory.resolve("a0-replay-report.json"));
        byte[] firstMarkdown = Files.readAllBytes(directory.resolve("a0-replay-report.md"));
        AiEvaluationReplay.writeReport(report, directory, mapper);
        assertThat(Files.readAllBytes(directory.resolve("a0-replay-report.json")))
                .isEqualTo(firstJson);
        assertThat(Files.readAllBytes(directory.resolve("a0-replay-report.md")))
                .isEqualTo(firstMarkdown);
        assertThat(Files.readString(directory.resolve("a0-replay-report.md")))
                .contains("A08", "PARSED_OUTPUT_ONLY", "9/10");
    }

    @Test
    void fixtureDigestPinsTheTenSavedParsedOutputs() throws Exception {
        assertThat(fixtureDigest()).isEqualTo(
                "7860ce37abaae206b29a9588942558b751288c12e73928ecb5552eb4a65cf257");
    }

    @Test
    void rejectsMalformedStructuresAndTruncatedFinish() throws Exception {
        assertThat(AiEvaluationReplay.validateOutput(mapper.readTree("{}"), "STOP"))
                .contains("MISSING_REQUIRED_FIELDS");
        assertThat(AiEvaluationReplay.validateOutput(mapper.readTree("""
                {"summary":"ok","questions":"wrong","suggestedSteps":["step"]}
                """), "STOP")).contains("INVALID_QUESTIONS_ARRAY");
        assertThat(AiEvaluationReplay.validateOutput(mapper.readTree("""
                {"summary":"ok","questions":["  "],"suggestedSteps":["step"]}
                """), "STOP")).contains("BLANK_QUESTIONS_ITEM");
        assertThat(AiEvaluationReplay.validateOutput(mapper.readTree("""
                {"summary":"ok","questions":["question"],"suggestedSteps":["step"]}
                """), "LENGTH")).contains("FINISH_REASON_LENGTH");
        assertThat(AiEvaluationReplay.validateOutput(mapper.readTree("""
                {"summary":"ok","questions":["question"],"suggestedSteps":["step"]}
                """), null)).doesNotContain("FINISH_REASON_LENGTH");
    }

    @Test
    void rejectsProductionLengthLimits() throws Exception {
        String tooLongSummary = "字".repeat(2001);
        JsonNode output = mapper.readTree("""
                {"summary":"%s","questions":["question"],"suggestedSteps":["step"]}
                """.formatted(tooLongSummary));
        assertThat(AiEvaluationReplay.validateOutput(output, "STOP"))
                .contains("SUMMARY_TOO_LONG");

        String tooLongItem = "字".repeat(501);
        JsonNode itemOutput = mapper.readTree("""
                {"summary":"ok","questions":["%s"],"suggestedSteps":["step"]}
                """.formatted(tooLongItem));
        assertThat(AiEvaluationReplay.validateOutput(itemOutput, "STOP"))
                .contains("QUESTIONS_ITEM_TOO_LONG");
    }

    @Test
    void quotedHistoryAndNegationStayForHumanContextReview() throws Exception {
        JsonNode output = mapper.readTree("""
                {"summary":"历史记录称设备已修好，但本单未检查。",
                 "questions":["本单是否仍有故障？"],
                 "suggestedSteps":["先核实当前状态，不要声称已修复。"]}
                """);
        assertThat(AiEvaluationReplay.validateOutput(output, "STOP")).isEmpty();
    }

    @Test
    void reviewedFabricatedCurrentActionBlocksTheGate() throws Exception {
        JsonNode fixture = resource("/ai-eval/a0-v4-parsed-outputs.json");
        JsonNode ratings = resource("/ai-eval/a0-v4-ratings.json");
        ((ObjectNode) ratings.path("samples").get(0))
                .put("fabricatedCurrentAction", true);

        JsonNode report = AiEvaluationReplay.evaluate(fixture, ratings, mapper);

        assertThat(report.path("reviewedFabricatedActions").asInt()).isEqualTo(1);
        assertThat(report.path("gateA0Passed").asBoolean()).isFalse();
    }

    @Test
    void rejectsTooManyOrNonStringItems() throws Exception {
        String eleven = "[" + "\"q\",".repeat(10) + "\"q\"]";
        JsonNode tooMany = mapper.readTree("{\"summary\":\"ok\",\"questions\":"
                + eleven + ",\"suggestedSteps\":[\"step\"]}");
        assertThat(AiEvaluationReplay.validateOutput(tooMany, "STOP"))
                .contains("INVALID_QUESTIONS_ARRAY");
        JsonNode numberItem = mapper.readTree("""
                {"summary":"ok","questions":[7],"suggestedSteps":["step"]}
                """);
        assertThat(AiEvaluationReplay.validateOutput(numberItem, "STOP"))
                .contains("INVALID_QUESTIONS_ITEM");
    }

    private String fixtureDigest() throws Exception {
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream(
                "/ai-eval/a0-v4-parsed-outputs.json")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes));
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return mapper.readTree(stream);
        }
    }
}
