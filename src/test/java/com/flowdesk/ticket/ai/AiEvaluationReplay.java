package com.flowdesk.ticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Offline evaluation of saved, parsed model outputs. Never calls a model. */
final class AiEvaluationReplay {
    private AiEvaluationReplay() {
    }

    static List<String> validateOutput(JsonNode output, String finishReason) {
        List<String> errors = new ArrayList<>();
        if (finishReason != null && !"STOP".equalsIgnoreCase(finishReason)) {
            errors.add("FINISH_REASON_" + finishReason.toUpperCase());
        }
        if (output == null || !output.isObject()
                || !output.has("summary") || !output.has("questions")
                || !output.has("suggestedSteps")) {
            errors.add("MISSING_REQUIRED_FIELDS");
            return errors;
        }
        JsonNode summary = output.get("summary");
        if (!summary.isTextual() || summary.asText().isBlank()) {
            errors.add("INVALID_SUMMARY");
        } else if (summary.asText().strip().length() > 2000) {
            errors.add("SUMMARY_TOO_LONG");
        }
        checkItems(output.get("questions"), "QUESTIONS", errors);
        checkItems(output.get("suggestedSteps"), "SUGGESTED_STEPS", errors);
        return List.copyOf(errors);
    }

    static ObjectNode evaluate(JsonNode fixture, JsonNode ratings, ObjectMapper mapper) {
        if (fixture == null || !fixture.path("samples").isArray()
                || ratings == null || !ratings.path("samples").isArray()) {
            throw new IllegalArgumentException("A0 fixture and ratings must contain samples arrays");
        }
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode rating : ratings.path("samples")) {
            String id = rating.path("id").asText();
            if (!rating.path("usable").isBoolean()
                    || !rating.path("fabricatedCurrentAction").isBoolean()
                    || rating.path("reason").asText().isBlank()
                    || byId.putIfAbsent(id, rating) != null) {
                throw new IllegalArgumentException("Invalid or duplicate human rating: " + id);
            }
        }
        ObjectNode report = mapper.createObjectNode();
        report.put("modelApiId", fixture.path("modelApiId").asText());
        report.put("promptVersion", fixture.path("promptVersion").asText());
        report.put("evidenceKind", "PARSED_OUTPUT_ONLY");
        report.put("finishReasonEvidence", "UNAVAILABLE");
        report.put("reviewer", ratings.path("reviewer").asText());
        ArrayNode results = report.putArray("samples");
        int passed = 0;
        int fabricated = 0;
        int usable = 0;
        int count = 0;
        for (JsonNode sample : fixture.path("samples")) {
            count++;
            String id = sample.path("id").asText();
            JsonNode rating = byId.remove(id);
            if (rating == null || !id.matches("A(0[1-9]|10)")) {
                throw new IllegalArgumentException("Missing rating or invalid sample ID: " + id);
            }
            List<String> errors = new ArrayList<>(validateOutput(sample.path("output"), null));
            if (!"OK".equals(sample.path("status").asText())) errors.add("SAMPLE_NOT_OK");
            if (!"deepseek-flash".equals(sample.path("output").path("model").asText())) errors.add("MODEL_MISMATCH");
            if (!"v4".equals(sample.path("output").path("promptVersion").asText())) errors.add("PROMPT_VERSION_MISMATCH");
            if (!validInput(sample.path("input"))) errors.add("INVALID_INPUT");
            if (errors.isEmpty()) passed++;
            if (rating.path("fabricatedCurrentAction").asBoolean()) fabricated++;
            if (rating.path("usable").asBoolean()) usable++;
            ObjectNode item = results.addObject();
            item.put("id", id);
            item.put("structurePassed", errors.isEmpty());
            item.set("machineErrors", mapper.valueToTree(errors));
            item.put("fabricatedCurrentAction", rating.path("fabricatedCurrentAction").asBoolean());
            item.put("usable", rating.path("usable").asBoolean());
            item.put("reviewReason", rating.path("reason").asText());
        }
        if (!byId.isEmpty() || count != 10) {
            throw new IllegalArgumentException("Expected exactly A01–A10 with one rating each");
        }
        report.put("sampleCount", count);
        report.put("structurePassed", passed);
        report.put("reviewedFabricatedActions", fabricated);
        report.put("slightlyEditable", usable);
        report.put("gateA0Passed", passed == 10 && fabricated == 0);
        return report;
    }

    static void writeReport(ObjectNode report, Path directory, ObjectMapper mapper) throws Exception {
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("a0-replay-report.json").toFile(), report);
        StringBuilder markdown = new StringBuilder()
                .append("# A0 v4 offline replay\n\n")
                .append("Evidence: PARSED_OUTPUT_ONLY. Saved output has no raw vendor JSON or finish reason; ")
                .append("this replay cannot prove the adapter's wire parser. No model call or API Key is used.\n\n")
                .append("Model: `").append(report.path("modelApiId").asText())
                .append("`; prompt: `").append(report.path("promptVersion").asText()).append("`.\n\n")
                .append("Structure: ").append(report.path("structurePassed").asInt())
                .append('/').append(report.path("sampleCount").asInt())
                .append("; reviewed fabricated current actions: ")
                .append(report.path("reviewedFabricatedActions").asInt())
                .append("; slightly editable: ")
                .append(report.path("slightlyEditable").asInt()).append('/')
                .append(report.path("sampleCount").asInt()).append(".\n\n")
                .append("| Sample | Structure | Usable | Current action invented | Review reason |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (JsonNode sample : report.path("samples")) {
            markdown.append("| ").append(sample.path("id").asText())
                    .append(" | ").append(sample.path("structurePassed").asBoolean() ? "PASS" : "FAIL")
                    .append(" | ").append(sample.path("usable").asBoolean() ? "YES" : "NO")
                    .append(" | ").append(sample.path("fabricatedCurrentAction").asBoolean() ? "YES" : "NO")
                    .append(" | ").append(sample.path("reviewReason").asText().replace("|", "\\|"))
                    .append(" |\n");
        }
        Files.writeString(directory.resolve("a0-replay-report.md"),
                markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void checkItems(JsonNode items, String name, List<String> errors) {
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > 10) {
            errors.add("INVALID_" + name + "_ARRAY");
            return;
        }
        for (JsonNode item : items) {
            if (!item.isTextual()) {
                errors.add("INVALID_" + name + "_ITEM");
            } else if (item.asText().isBlank()) {
                errors.add("BLANK_" + name + "_ITEM");
            } else if (item.asText().strip().length() > 500) {
                errors.add(name + "_ITEM_TOO_LONG");
            }
        }
    }

    private static boolean validInput(JsonNode input) {
        if (input == null || !input.isObject()) return false;
        for (String field : List.of("title", "description", "category", "priority", "status")) {
            if (!input.path(field).isTextual() || input.path(field).asText().isBlank()) return false;
        }
        return true;
    }
}
