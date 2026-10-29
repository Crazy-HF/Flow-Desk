package com.flowdesk.ticket.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定 A1 配对样本的输入与来源边界；本测试不连接数据库或模型。 */
class A1EvaluationFixtureTest {
    private static final Pattern TICKET_NO = Pattern.compile("FD-\\d{8}-\\d+");
    private static final Set<String> KINDS = Set.of("S", "R", "N", "C");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sixteenPairedCasesReuseTheExactA0InputsAndKeepSourcesWithinProductionBounds()
            throws Exception {
        JsonNode a0 = resource("/ai-eval/a0-v4-parsed-outputs.json");
        JsonNode a1 = resource("/ai-eval/a1-l1-cases.json");
        Map<String, JsonNode> originalInputs = new HashMap<>();
        for (JsonNode sample : a0.path("samples")) {
            originalInputs.put(sample.path("id").asText(), sample.path("input"));
        }

        assertThat(a1.path("version").asText()).isEqualTo("a1-l1-fixture-v1");
        assertThat(a1.path("baselineFixture").asText())
                .isEqualTo("a0-v4-parsed-outputs.json");
        assertThat(a1.path("samples").size()).isEqualTo(16);
        Set<String> ids = new HashSet<>();
        Map<String, Integer> kindCounts = new HashMap<>();
        for (JsonNode sample : a1.path("samples")) {
            String id = sample.path("id").asText();
            String kind = sample.path("kind").asText();
            assertThat(ids.add(id)).as("duplicate sample %s", id).isTrue();
            assertThat(KINDS).contains(kind);
            kindCounts.merge(kind, 1, Integer::sum);
            assertThat(sample.path("goal").asText()).as(id).isNotBlank();

            JsonNode input = originalInputs.getOrDefault(id, sample.path("input"));
            if (originalInputs.containsKey(id)) {
                // A0 的本单字段是配对比较的唯一基线，A1 不能悄悄改写。
                assertThat(sample.has("input")).as(id).isFalse();
            } else {
                assertThat(id).matches("[SRNC]\\d{2}");
            }
            assertThat(input.isObject()).as(id).isTrue();
            for (String field : List.of("title", "description", "category", "priority", "status")) {
                assertThat(input.path(field).isTextual()).as("%s.%s", id, field).isTrue();
                assertThat(input.path(field).asText()).as("%s.%s", id, field).isNotBlank();
            }
            assertThat(input.path("status").asText()).as(id).isEqualTo("PROCESSING");
            assertThat(TICKET_NO.matcher(input.toString()).find()).as(id).isFalse();

            JsonNode sources = sample.path("sources");
            assertThat(sources.isArray()).as(id).isTrue();
            assertThat(sources.size()).as(id).isBetween(0, 5);
            assertThat(sources.isEmpty()).as(id).isEqualTo("N".equals(kind));
            int totalExcerptLength = 0;
            for (int index = 0; index < sources.size(); index++) {
                JsonNode source = sources.get(index);
                assertThat(source.path("label").asText()).as(id)
                        .isEqualTo("[S" + (index + 1) + "]");
                assertThat(source.path("title").asText()).as(id).isNotBlank();
                String excerpt = source.path("processExcerpt").asText();
                assertThat(excerpt).as(id).isNotBlank();
                assertThat(excerpt.length()).as(id).isLessThanOrEqualTo(500);
                assertThat(TICKET_NO.matcher(source.toString()).find()).as(id).isFalse();
                totalExcerptLength += excerpt.length();
            }
            assertThat(totalExcerptLength).as(id).isLessThanOrEqualTo(2000);
        }
        assertThat(ids).containsAll(originalInputs.keySet());
        assertThat(kindCounts).containsKeys("S", "R", "N", "C");
        assertThat(kindCounts.get("S")).isGreaterThanOrEqualTo(2);
        assertThat(kindCounts.get("N")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void fixtureDigestPinsTheReviewableSourceSet() throws Exception {
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream("/ai-eval/a1-l1-cases.json")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("66d879b411bf90b3a37d077add4134091885e704a9cddd37ad0eb74f79d2de7a");
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return mapper.readTree(stream);
        }
    }
}
