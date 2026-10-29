package com.flowdesk.ticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读取已保存的 A1 配对输出；引用真实性和可用性仍须逐条人工审定。 */
public final class A1PairedReplay {
    private static final Pattern LABEL_START = Pattern.compile("\\[\\s*[sS]");
    private static final Pattern CANONICAL_LABEL = Pattern.compile("\\[S(\\d+)\\]");
    private static final Pattern TICKET_NO = Pattern.compile("FD-\\d{8}-\\d+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private A1PairedReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("用法：A1PairedReplay [配对记录 JSON 路径]");
        }
        Path recordPath = args.length == 1 ? Path.of(args[0])
                : Path.of("target", "ai-eval", "a1-paired-record.json");
        JsonNode fixture = resource("/ai-eval/a1-l1-cases.json");
        JsonNode baseline = resource("/ai-eval/a0-v4-parsed-outputs.json");
        ObjectNode report = evaluate(fixture, baseline, JSON.readTree(recordPath.toFile()));
        Path output = Path.of("target", "ai-eval", "a1-machine-report.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("机器判定=" + output.toAbsolutePath()
                + "；人工来源真实性和可用性仍待审定");
    }

    static ObjectNode evaluate(JsonNode fixture, JsonNode baseline, JsonNode record) throws Exception {
        if (!sha256("/ai-eval/a1-l1-cases.json")
                    .equals(record.path("fixtureSha256").asText())
                || !sha256("/ai-eval/a0-v4-parsed-outputs.json")
                    .equals(record.path("baselineFixtureSha256").asText())) {
            throw new IllegalArgumentException("配对记录与当前固定样本的哈希不一致");
        }
        Map<String, JsonNode> baselineInputs = new HashMap<>();
        for (JsonNode sample : baseline.path("samples")) {
            baselineInputs.put(sample.path("id").asText(), sample.path("input"));
        }
        Map<String, JsonNode> recorded = new HashMap<>();
        for (JsonNode sample : record.path("samples")) {
            if (recorded.putIfAbsent(sample.path("id").asText(), sample) != null) {
                throw new IllegalArgumentException("配对记录含重复样本");
            }
        }
        ObjectNode report = JSON.createObjectNode();
        report.put("evidenceKind", "PAID_PARSED_OUTPUT_MACHINE_CHECK");
        report.put("fixtureSha256", record.path("fixtureSha256").asText());
        report.put("humanReviewPending", true);
        ArrayNode items = report.putArray("samples");
        int paired = 0;
        int machinePassed = 0;
        long baselineTokens = 0;
        long l1Tokens = 0;
        int tokenPairs = 0;
        for (JsonNode sample : fixture.path("samples")) {
            String id = sample.path("id").asText();
            JsonNode expectedInput = baselineInputs.getOrDefault(id, sample.path("input"));
            JsonNode found = recorded.remove(id);
            List<String> errors = new ArrayList<>();
            if (found == null) {
                errors.add("MISSING_RECORD");
            } else {
                if (!expectedInput.equals(found.path("input"))
                        || !sample.path("sources").equals(found.path("sources"))) {
                    errors.add("FIXTURE_INPUT_MISMATCH");
                }
                checkArm(found.path("baseline"), "v4", 0, "BASELINE", errors);
                int sources = sample.path("sources").size();
                checkArm(found.path("l1"), sources == 0 ? "v4" : "v5",
                        sources, "L1", errors);
                if ("OK".equals(found.path("baseline").path("status").asText())
                        && "OK".equals(found.path("l1").path("status").asText())) {
                    paired++;
                    int b = found.path("baseline").path("output").path("inputTokens").asInt(0);
                    int l = found.path("l1").path("output").path("inputTokens").asInt(0);
                    if (b > 0 && l > 0) {
                        baselineTokens += b;
                        l1Tokens += l;
                        tokenPairs++;
                    }
                }
            }
            ObjectNode item = items.addObject();
            item.put("id", id);
            item.put("kind", sample.path("kind").asText());
            item.set("machineErrors", JSON.valueToTree(errors));
            item.put("machinePassed", errors.isEmpty());
            if (errors.isEmpty()) machinePassed++;
        }
        if (!recorded.isEmpty()) {
            throw new IllegalArgumentException("配对记录含固定样本以外的 ID：" + recorded.keySet());
        }
        report.put("sampleCount", fixture.path("samples").size());
        report.put("pairedCount", paired);
        report.put("machinePassedCount", machinePassed);
        report.put("tokenPairCount", tokenPairs);
        if (tokenPairs > 0 && baselineTokens > 0) {
            report.put("averageInputTokenRatio", (double) l1Tokens / baselineTokens);
        }
        // 机器检查不能证明引用真实、注入未被服从或建议稍改即用。
        report.put("gateA1Passed", false);
        return report;
    }

    private static void checkArm(JsonNode arm, String promptVersion, int sourceCount,
                                 String name, List<String> errors) {
        if (!"OK".equals(arm.path("status").asText())) {
            errors.add(name + "_NOT_OK");
            return;
        }
        JsonNode output = arm.path("output");
        for (String error : AiEvaluationReplay.validateOutput(output, null)) {
            errors.add(name + "_" + error);
        }
        if (!promptVersion.equals(output.path("promptVersion").asText())) {
            errors.add(name + "_PROMPT_VERSION");
        }
        if (!"deepseek-flash".equals(output.path("model").asText())) {
            errors.add(name + "_MODEL");
        }
        if (output.path("inputTokens").asInt(0) <= 0) {
            errors.add(name + "_INPUT_TOKENS_MISSING");
        }
        if (arm.path("elapsedMs").asLong(0) <= 0) {
            errors.add(name + "_ELAPSED_MISSING");
        }
        if (!arm.has("balanceBeforeCny") || !arm.has("balanceAfterCny")) {
            errors.add(name + "_BALANCE_INCOMPLETE");
        }
        for (JsonNode text : output.path("questions")) checkReferences(text.asText(), sourceCount, name, errors);
        for (JsonNode text : output.path("suggestedSteps")) checkReferences(text.asText(), sourceCount, name, errors);
        checkReferences(output.path("summary").asText(), sourceCount, name, errors);
    }

    private static void checkReferences(String text, int sourceCount,
                                        String name, List<String> errors) {
        Matcher candidate = LABEL_START.matcher(text);
        while (candidate.find()) {
            Matcher label = CANONICAL_LABEL.matcher(text);
            label.region(candidate.start(), text.length());
            if (!label.lookingAt()) {
                errors.add(name + "_MALFORMED_LABEL");
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(label.group(1));
            } catch (NumberFormatException invalid) {
                errors.add(name + "_INVALID_LABEL");
                continue;
            }
            if (number < 1 || number > sourceCount) {
                errors.add(name + "_UNKNOWN_LABEL");
            }
        }
        if (TICKET_NO.matcher(text).find()) {
            errors.add(name + "_BARE_TICKET_NO");
        }
    }

    private static JsonNode resource(String name) throws Exception {
        try (InputStream stream = A1PairedReplay.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("缺少固定样本：" + name);
            return JSON.readTree(stream);
        }
    }

    private static String sha256(String name) throws Exception {
        try (InputStream stream = A1PairedReplay.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("缺少固定样本：" + name);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stream.readAllBytes()));
        }
    }
}
