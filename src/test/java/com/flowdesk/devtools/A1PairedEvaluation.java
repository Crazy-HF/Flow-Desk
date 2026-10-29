package com.flowdesk.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import com.flowdesk.ticket.infrastructure.DeepSeekTicketAiModelAdapter;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** A1 手动付费记录入口。一次只执行一个样本的一侧，绝不自动重试。 */
public final class A1PairedEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPORT = Path.of("target", "ai-eval", "a1-paired-record.json");
    private static final String A1_RESOURCE = "/ai-eval/a1-l1-cases.json";
    private static final String A0_RESOURCE = "/ai-eval/a0-v4-parsed-outputs.json";

    private A1PairedEvaluation() {
    }

    static BigDecimal requireA1Authorization(String evaluation, String record, String a1Record,
                                             String enabled, String key, String budget) {
        if (!"YES".equals(a1Record)) {
            throw new IllegalStateException("A1 付费记录还需要 FLOWDESK_AI_A1_RECORD=YES");
        }
        return DeepSeekBatchEvaluation.requireRecordAuthorization(
                evaluation, record, enabled, key, budget);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("baseline", "l1").contains(args[1])) {
            throw new IllegalArgumentException("用法：A1PairedEvaluation <样本 ID> <baseline|l1>");
        }
        String key = System.getenv("DEEPSEEK_API_KEY");
        BigDecimal budget = requireA1Authorization(
                System.getenv("FLOWDESK_AI_EVALUATION"),
                System.getenv("FLOWDESK_AI_RECORD"),
                System.getenv("FLOWDESK_AI_A1_RECORD"),
                System.getenv("FLOWDESK_AI_ENABLED"), key,
                System.getenv("FLOWDESK_AI_REMAINING_BUDGET_CNY"));

        byte[] a1Bytes = resourceBytes(A1_RESOURCE);
        byte[] a0Bytes = resourceBytes(A0_RESOURCE);
        JsonNode a1 = JSON.readTree(a1Bytes);
        JsonNode a0 = JSON.readTree(a0Bytes);
        JsonNode sample = findSample(a1.path("samples"), args[0]);
        JsonNode input = sample.has("input")
                ? sample.path("input")
                : findSample(a0.path("samples"), args[0]).path("input");
        String arm = args[1];
        String fixtureHash = sha256(a1Bytes);
        String baselineHash = sha256(a0Bytes);

        boolean existing = Files.exists(REPORT);
        ObjectNode report = existing
                ? (ObjectNode) JSON.readTree(REPORT.toFile())
                : JSON.createObjectNode();
        if (existing && !report.has("fixtureSha256")) {
            throw new IllegalStateException("已有 A1 报告缺少样本哈希，拒绝覆盖");
        }
        if (report.has("fixtureSha256")) {
            if (!fixtureHash.equals(report.path("fixtureSha256").asText())
                    || !baselineHash.equals(report.path("baselineFixtureSha256").asText())) {
                throw new IllegalStateException("评估样本已变化，不能续写旧报告");
            }
        } else {
            report.put("evidenceKind", "PAID_PARSED_OUTPUT");
            report.put("modelApiId", "deepseek-flash");
            report.put("parserContract", "json-object-required-three-fields-stop-v1");
            report.put("fixtureVersion", a1.path("version").asText());
            report.put("fixtureSha256", fixtureHash);
            report.put("baselineFixtureSha256", baselineHash);
            report.putArray("samples");
        }
        ObjectNode item = findOrCreateRecord(report.withArray("samples"), sample, input);
        if (!input.equals(item.path("input"))
                || !sample.path("sources").equals(item.path("sources"))) {
            throw new IllegalStateException("已有样本输入或来源与固定样本不一致");
        }
        if (item.has(arm)) {
            // STARTED 也可能已发出付费请求；必须人工核实后才能决定是否重录。
            throw new IllegalStateException("该样本的 " + arm + " 已有记录，拒绝自动重试");
        }
        if ("l1".equals(arm) && !"OK".equals(item.path("baseline").path("status").asText())) {
            throw new IllegalStateException("须先完成同一样本的 baseline");
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        BigDecimal before = DeepSeekBatchEvaluation.balance(http, key);
        BigDecimal initial = report.has("baselineBalanceCny")
                ? report.path("baselineBalanceCny").decimalValue() : before;
        DeepSeekBatchEvaluation.canSpend(budget, initial.subtract(before));
        report.put("baselineBalanceCny", initial);

        ObjectNode attempt = item.putObject(arm);
        attempt.put("status", "STARTED");
        attempt.put("startedAt", Instant.now().toString());
        attempt.put("balanceBeforeCny", before);
        save(report); // 先落盘，再发出可能收费的调用。

        TicketAiModelPort.Input modelInput = modelInput(input, sample.path("sources"), arm);
        TicketAiModelPort model = new DeepSeekTicketAiModelAdapter(key, JSON);
        long began = System.nanoTime();
        try {
            TicketAiModelPort.Output output = model.generate(modelInput);
            attempt.set("output", JSON.valueToTree(output));
            attempt.put("status", "OK");
        } catch (TicketAiModelPort.ModelFailure failure) {
            attempt.put("status", "FAILED");
            attempt.put("failureKind", failure.kind().name());
        } catch (RuntimeException failure) {
            attempt.put("status", "FAILED");
            attempt.put("failureKind", "UNEXPECTED_" + failure.getClass().getSimpleName());
        }
        attempt.put("elapsedMs", (System.nanoTime() - began) / 1_000_000);
        save(report); // 余额接口故障也不能丢失已取得的模型输出。
        BigDecimal after = DeepSeekBatchEvaluation.balance(http, key);
        attempt.put("balanceAfterCny", after);
        attempt.put("observedBalanceChangeCny", before.subtract(after));
        report.put("latestBalanceCny", after);
        save(report);
        System.out.println(args[0] + " " + arm + " " + attempt.path("status").asText()
                + "，报告=" + REPORT.toAbsolutePath());
    }

    static TicketAiModelPort.Input modelInput(JsonNode input, JsonNode sources, String arm) {
        List<TicketAiModelPort.Source> modelSources = new ArrayList<>();
        if ("l1".equals(arm)) {
            for (JsonNode source : sources) {
                modelSources.add(new TicketAiModelPort.Source(
                        source.path("label").asText(), source.path("title").asText(),
                        source.path("processExcerpt").asText()));
            }
        }
        return new TicketAiModelPort.Input(
                input.path("title").asText(), input.path("description").asText(),
                input.path("category").asText(), input.path("priority").asText(),
                input.path("status").asText(), modelSources);
    }

    private static ObjectNode findOrCreateRecord(ArrayNode records, JsonNode sample, JsonNode input) {
        String id = sample.path("id").asText();
        for (JsonNode node : records) {
            if (id.equals(node.path("id").asText())) {
                return (ObjectNode) node;
            }
        }
        ObjectNode item = records.addObject();
        item.put("id", id);
        item.put("kind", sample.path("kind").asText());
        item.put("goal", sample.path("goal").asText());
        item.set("input", input.deepCopy());
        item.set("sources", sample.path("sources").deepCopy());
        return item;
    }

    private static JsonNode findSample(JsonNode samples, String id) {
        for (JsonNode sample : samples) {
            if (id.equals(sample.path("id").asText())) {
                return sample;
            }
        }
        throw new IllegalArgumentException("未知 A1 样本：" + id);
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream stream = A1PairedEvaluation.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("缺少固定样本：" + name);
            }
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void save(ObjectNode report) throws Exception {
        Files.createDirectories(REPORT.getParent());
        Path temporary = REPORT.resolveSibling(REPORT.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, REPORT, StandardCopyOption.REPLACE_EXISTING);
    }
}
