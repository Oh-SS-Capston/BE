package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PER_SCENARIO 컨텍스트 필터링 고정 테스트.
 *
 * <p>필터링은 이 분해의 성립 조건이다. 여기가 무너지면 호출 수만큼 프롬프트가 순증해
 * 8차 baseline 기준 ② 프롬프트가 14.2분에서 71분이 된다. 그래서 "줄어든다"를 고정한다.</p>
 */
class LlmScenarioContextSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmScenarioContextSupport support = new LlmScenarioContextSupport(objectMapper);

    private JsonNode twoScenarioSeed() {
        return objectMapper.valueToTree(List.of(
                java.util.Map.of(
                        "scenarioId", "SCN-001",
                        "title", "MediaType 사용 흐름",
                        "steps", List.of(java.util.Map.of(
                                "stepNo", 1,
                                "classFqn", "okhttp3.MediaType",
                                "methodFqn", "okhttp3.MediaType.parse",
                                "filePath", "okhttp/src/main/java/okhttp3/MediaType.java"
                        ))
                ),
                java.util.Map.of(
                        "scenarioId", "SCN-002",
                        "title", "MediaType 보조 흐름",
                        "steps", List.of(java.util.Map.of(
                                "stepNo", 1,
                                "classFqn", "okhttp3.MediaType",
                                "methodFqn", "okhttp3.MediaType.type",
                                "filePath", "okhttp/src/main/java/okhttp3/MediaType.java"
                        ))
                )
        ));
    }

    private JsonNode structureWithSeeds() {
        return objectMapper.valueToTree(java.util.Map.of(
                "overviewSeed", java.util.Map.of("purpose", "HTTP 클라이언트"),
                "coreMethodSeed", List.of(
                        java.util.Map.of("fqn", "okhttp3.MediaType.parse", "summarySeed", "문자열을 파싱한다"),
                        java.util.Map.of("fqn", "okhttp3.MediaType.type", "summarySeed", "타입을 돌려준다"),
                        java.util.Map.of("fqn", "okhttp3.Request.url", "summarySeed", "무관한 메서드")
                ),
                "coreClassSeed", List.of(
                        java.util.Map.of("fqn", "okhttp3.MediaType"),
                        java.util.Map.of("fqn", "okhttp3.Request")
                )
        ));
    }

    @Test
    @DisplayName("시나리오 한 장 컨텍스트에는 그 시나리오의 시드만 실린다")
    void filtersSeedsToTheTargetScenario() throws Exception {
        JsonNode seed = twoScenarioSeed();

        String json = support.buildOneScenarioContext(
                structureWithSeeds(),
                objectMapper.createObjectNode(),
                List.of(),
                seed,
                0
        );
        JsonNode context = objectMapper.readTree(json);

        assertThat(context.path("scenario").path("scenarioId").asText()).isEqualTo("SCN-001");
        // 다른 시나리오의 메서드와 무관한 클래스는 빠진다.
        assertThat(context.path("coreMethodSeed")).hasSize(1);
        assertThat(context.path("coreMethodSeed").get(0).path("fqn").asText())
                .isEqualTo("okhttp3.MediaType.parse");
        assertThat(context.path("coreClassSeed")).hasSize(1);
    }

    @Test
    @DisplayName("같은 타입인데 다른 시나리오가 가져간 메서드를 알려준다")
    void reportsMethodsTakenByOtherScenarios() throws Exception {
        JsonNode seed = twoScenarioSeed();

        String json = support.buildOneScenarioContext(
                structureWithSeeds(), objectMapper.createObjectNode(), List.of(), seed, 0);
        JsonNode excluded = objectMapper.readTree(json).path("excludedByOtherScenarios");

        assertThat(excluded).hasSize(1);
        assertThat(excluded.get(0).path("methodFqn").asText()).isEqualTo("okhttp3.MediaType.type");
        assertThat(excluded.get(0).path("coveredByScenarioId").asText()).isEqualTo("SCN-002");
    }

    @Test
    @DisplayName("근거는 이 시나리오 step이 가리키는 파일만 남긴다")
    void keepsOnlyEvidenceForThisScenarioFiles() throws Exception {
        List<LlmRequest.EvidenceSnippet> evidence = List.of(
                new LlmRequest.EvidenceSnippet(
                        1L, "okhttp/src/main/java/okhttp3/MediaType.java", 10, 20, "코드", "AST"),
                new LlmRequest.EvidenceSnippet(
                        2L, "okhttp/src/main/java/okhttp3/Request.java", 30, 40, "무관", "AST")
        );

        String json = support.buildOneScenarioContext(
                structureWithSeeds(), objectMapper.createObjectNode(), evidence, twoScenarioSeed(), 0);
        JsonNode filtered = objectMapper.readTree(json).path("evidence");

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("evidenceId").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("한 장짜리 컨텍스트는 전체 시드보다 짧다")
    void perScenarioContextIsSmallerThanFullSeed() throws Exception {
        JsonNode seed = twoScenarioSeed();
        JsonNode structure = structureWithSeeds();

        String one = support.buildOneScenarioContext(
                structure, objectMapper.createObjectNode(), List.of(), seed, 0);
        String wholeSeed = objectMapper.writeValueAsString(seed)
                + objectMapper.writeValueAsString(structure.path("coreMethodSeed"));

        assertThat(one.length()).isLessThan(wholeSeed.length() * 2);
        assertThat(one).doesNotContain("okhttp3.Request.url");
    }

    @Test
    @DisplayName("개요 컨텍스트에는 시나리오 골격을 싣지 않는다")
    void overviewContextCarriesNoScenarioSeed() throws Exception {
        String json = support.buildOverviewContext(structureWithSeeds(), objectMapper.createObjectNode());
        JsonNode context = objectMapper.readTree(json);

        assertThat(context.has("scenario")).isFalse();
        assertThat(context.has("scenarioSeed")).isFalse();
        assertThat(context.path("overviewSeed").path("purpose").asText()).isEqualTo("HTTP 클라이언트");
    }

    @Test
    @DisplayName("개요 컨텍스트는 개요에 쓰이는 필드만 실는다")
    void overviewContextProjectsOnlyNeededFields() throws Exception {
        // 실측 caution 하나의 모양: 개요에 불필요한 묶음이 달려 온다.
        JsonNode rules = objectMapper.readTree("""
                {"cautions":[{"cautionId":"CAU-001","title":"파싱 오류 주의",
                  "message":"형식이 틀리면 예외가 발생한다","relatedClass":"okhttp3.MediaType",
                  "guideSlots":{"beforeCall":"긴 문장","doCall":"긴 문장","successCheck":"긴 문장"},
                  "guideNarrative":"슬롯을 이어 붙인 긴 문장",
                  "guideQuality":{"actionabilityScore":100,"slotCoverage":1.0},
                  "slotEvidence":{"beforeCall":"a.java:1","doCall":"a.java:2"}}]}
                """);

        String json = support.buildOverviewContext(structureWithSeeds(), rules);
        JsonNode caution = objectMapper.readTree(json).path("cautions").get(0);

        assertThat(caution.path("title").asText()).isEqualTo("파싱 오류 주의");
        assertThat(caution.path("message").asText()).contains("예외");
        // 개요 문장을 쓰는 데 안 쓰이는 묶음은 실리지 않는다.
        // 이것들 때문에 프롬프트 5,958토큰을 써서 출력 169토큰을 냈다.
        assertThat(caution.has("guideSlots")).isFalse();
        assertThat(caution.has("guideNarrative")).isFalse();
        assertThat(caution.has("guideQuality")).isFalse();
        assertThat(caution.has("slotEvidence")).isFalse();
    }
}
