package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmServiceBuildSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmServiceBuildSupport support =
            new LlmServiceBuildSupport(objectMapper, new com.example.ossdoc.global.llm.config.LlmGenerationProperties());

    @Test
    void normalizeScenarioSpecs_preservesRichStepFields() throws Exception {
        JsonNode raw = richScenarioResponse();

        JsonNode normalized = support.normalizeScenarioSpecs(raw, structureWithMethodSeed());

        JsonNode overview = normalized.path("overview");
        JsonNode step = normalized.path("scenarios").get(0).path("steps").get(0);

        assertThat(overview.path("architectureSummary").asText()).contains("Worker builds graph facts");
        assertThat(step.path("action").asText()).contains("createRun");
        assertThat(step.path("successSignal").asText()).contains("queued");
        assertThat(step.path("evidenceInterpretation").asText()).contains("request DTO");
        assertThat(step.path("guideSlots").path("doCall").asText()).contains("createRun");
        assertThat(step.path("guideNarrative").asText()).contains("Fix the URL");
    }

    private JsonNode richScenarioResponse() throws Exception {
        return objectMapper.readTree("""
                {
                  "overview": {
                    "project": "ossdoc",
                    "purpose": "Explain code from graph evidence",
                    "architectureSummary": "Worker builds graph facts before LLM refinement",
                    "dataFlow": "facts -> graph -> scenario"
                  },
                  "scenarios": [{
                    "scenarioId": "SCN-001",
                    "title": "Analyze repository",
                    "intent": "Run analysis from a repository URL",
                    "whyThisMatters": "Users need evidence-backed onboarding",
                    "steps": [{
                      "stepNo": 1,
                      "description": "Check the repository URL and run the analysis request.",
                      "precondition": "A repository URL is available.",
                      "action": "Call RepoRunService.createRun with the repository URL.",
                      "successSignal": "The run status changes to queued.",
                      "failureSignal": "Invalid URL validation rejects the request.",
                      "userAction": "Fix the URL and retry.",
                      "dataHandled": "repoUrl",
                      "evidenceInterpretation": "The method creates the run command from the request DTO.",
                      "classFqn": "com.example.ossdoc.domain.run.service.RepoRunService",
                      "methodFqn": "com.example.ossdoc.domain.run.service.RepoRunService.createRun",
                      "confidence": 0.91,
                      "evidenceLinks": [{"evidenceId": 7, "filePath": "src/main/java/RepoRunService.java", "lines": "10-30"}]
                    }]
                  }],
                  "methodFlow": []
                }
                """);
    }

    @Test
    void normalizeScenarioSpecs_leavesMissingSlotsEmptyInsteadOfInjectingForbiddenFiller() throws Exception {
        // 모델이 description만 쓰고 나머지 서술 필드를 누락한 실제 응답 형태.
        JsonNode raw = objectMapper.readTree("""
                {
                  "overview": {"project": "ossdoc"},
                  "scenarios": [{
                    "scenarioId": "SCN-001",
                    "title": "Analyze repository",
                    "steps": [{"stepNo": 1, "description": "Check the repository URL."}]
                  }],
                  "methodFlow": []
                }
                """);

        JsonNode step = support.normalizeScenarioSpecs(raw, structureWithMethodSeed())
                .path("scenarios").get(0).path("steps").get(0);

        // 프롬프트가 금지한 문구를 코드가 대신 채워 넣지 않는다.
        assertThat(step.path("guideNarrative").asText()).doesNotContain("핵심 동작 수행");
        assertThat(step.path("guideSlots").path("successCheck").asText()).isEmpty();
        assertThat(step.path("guideSlots").path("failureSymptom").asText()).isEmpty();
        assertThat(step.path("guideSlots").path("nextAction").asText()).isEmpty();

        // 빈 슬롯이 지표에 그대로 드러난다(before/call만 채워져 2/5).
        assertThat(step.path("guideQuality").path("slotCoverage").asDouble()).isEqualTo(0.4d);
        assertThat(step.path("guideQuality").path("forbiddenPhraseRate").asDouble()).isEqualTo(0.0d);
        assertThat(step.path("guideQuality").path("meetsThreshold").asBoolean()).isFalse();
    }

    @Test
    void normalizeScenarioSpecs_reportsFullSlotCoverageWhenModelFillsEveryField() throws Exception {
        JsonNode normalized = support.normalizeScenarioSpecs(richScenarioResponse(), structureWithMethodSeed());
        JsonNode step = normalized.path("scenarios").get(0).path("steps").get(0);

        assertThat(step.path("guideQuality").path("slotCoverage").asDouble()).isEqualTo(1.0d);
    }

    @Test
    void normalizeScenarioSpecs_keepsSeedSkeletonWhenModelSwapsTheTargetMethod() throws Exception {
        // 모델이 골격에 없는 메서드로 바꿔치기하고 시나리오 하나를 통째로 빠뜨린 응답.
        JsonNode raw = objectMapper.readTree("""
                {
                  "overview": {"project": "ossdoc"},
                  "scenarios": [{
                    "scenarioId": "SCN-001",
                    "title": "모델이 붙인 제목",
                    "steps": [{
                      "stepNo": 1,
                      "description": "요청을 만든다.",
                      "action": "createRun을 호출한다.",
                      "methodFqn": "com.example.Hallucinated.method",
                      "classFqn": "com.example.Hallucinated",
                      "evidenceLinks": [{"evidenceId": 3}]
                    }]
                  }]
                }
                """);

        JsonNode scenarios = support.normalizeScenarioSpecs(raw, structureWithScenarioSeed()).path("scenarios");

        // 골격이 정한 시나리오 수가 유지된다. 모델이 빠뜨린 SCN-002도 살아남는다.
        assertThat(scenarios.size()).isEqualTo(2);
        assertThat(scenarios.get(1).path("scenarioId").asText()).isEqualTo("SCN-002");

        JsonNode step = scenarios.get(0).path("steps").get(0);
        // 서술은 모델 것을 쓰고, 대상 메서드와 근거는 골격이 되돌린다.
        assertThat(step.path("action").asText()).contains("createRun");
        assertThat(step.path("methodFqn").asText()).isEqualTo("com.acme.Builder.required");
        assertThat(step.path("classFqn").asText()).isEqualTo("com.acme.Builder");
        assertThat(step.path("evidenceLinks").get(0).path("filePath").asText())
                .isEqualTo("src/main/java/com/acme/Builder.java");
    }

    @Test
    void normalizeScenarioSpecs_doesNotShareTheSameMethodAcrossDifferentScenarios() throws Exception {
        // 모델이 두 시나리오 모두 step 1만 보냈다. 예전에는 둘 다 methodFlow[0]을 물려받았다.
        JsonNode raw = objectMapper.readTree("""
                {
                  "overview": {"project": "ossdoc"},
                  "scenarios": [
                    {"scenarioId": "SCN-001", "steps": [{"stepNo": 1, "description": "첫 흐름"}]},
                    {"scenarioId": "SCN-002", "steps": [{"stepNo": 1, "description": "둘째 흐름"}]}
                  ]
                }
                """);

        JsonNode scenarios = support.normalizeScenarioSpecs(raw, structureWithScenarioSeed()).path("scenarios");

        String first = scenarios.get(0).path("steps").get(0).path("methodFqn").asText();
        String second = scenarios.get(1).path("steps").get(0).path("methodFqn").asText();
        assertThat(first).isEqualTo("com.acme.Builder.required");
        assertThat(second).isEqualTo("com.acme.Parser.parse");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void fallbackScenarioSpecs_keepsSeedSkeletonInsteadOfCollapsingToOneScenario() {
        JsonNode out = support.fallbackScenarioSpecs(structureWithScenarioSeed(), objectMapper.createObjectNode());

        assertThat(out.path("fallbackApplied").asBoolean()).isTrue();
        assertThat(out.path("scenarios").size()).isEqualTo(2);
        // 모델 서술이 없으면 골격의 summarySeed가 description이 된다.
        assertThat(out.path("scenarios").get(0).path("steps").get(0).path("description").asText())
                .contains("필수 옵션");
    }

    private ObjectNode structureWithScenarioSeed() {
        ObjectNode structure = structureWithMethodSeed();
        ArrayNode scenarioSeed = structure.putArray("scenarioSeed");

        ObjectNode first = scenarioSeed.addObject();
        first.put("scenarioId", "SCN-001");
        first.put("title", "대표 호출 흐름");
        first.put("intent", "핵심 API를 순서대로 호출해 기본 기능을 완성한다.");
        first.putArray("steps").addObject()
                .put("stepNo", 1)
                .put("classFqn", "com.acme.Builder")
                .put("methodFqn", "com.acme.Builder.required")
                .put("summarySeed", "필수 옵션을 등록한다.")
                .put("filePath", "src/main/java/com/acme/Builder.java")
                .put("startLine", 10)
                .put("endLine", 20);

        ObjectNode second = scenarioSeed.addObject();
        second.put("scenarioId", "SCN-002");
        second.put("title", "Parser 사용 흐름");
        second.put("intent", "Parser의 공개 메서드를 순서대로 사용한다.");
        second.putArray("steps").addObject()
                .put("stepNo", 1)
                .put("classFqn", "com.acme.Parser")
                .put("methodFqn", "com.acme.Parser.parse")
                .put("summarySeed", "입력 인자를 해석한다.")
                .put("filePath", "src/main/java/com/acme/Parser.java")
                .put("startLine", 30)
                .put("endLine", 55);

        return structure;
    }

    @Test
    void buildCoreMethodCards_usesScenarioStepNarrative() {
        ArrayNode scenarios = objectMapper.createArrayNode();
        scenarios.addObject()
                .put("scenarioId", "SCN-001")
                .put("title", "Analyze repository")
                .put("intent", "Run analysis from a repository URL")
                .set("steps", scenarioSteps());

        ArrayNode cards = support.buildCoreMethodCards(
                structureWithMethodSeed().path("coreMethodSeed"),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                scenarios
        );

        JsonNode card = cards.get(0);

        assertThat(card.path("llmEnhanced").asBoolean()).isTrue();
        assertThat(card.path("whatItDoes").asText()).contains("createRun");
        assertThat(card.path("guideSlots").path("failureSymptom").asText()).contains("Invalid URL");
        assertThat(card.path("usageScenario").path("evidenceInterpretation").asText()).contains("request DTO");
    }

    @Test
    void buildCoreMethodCards_keepsTargetSuitabilityGateWhileMergingScenarioText() {
        ArrayNode scenarios = objectMapper.createArrayNode();
        scenarios.addObject()
                .put("scenarioId", "SCN-001")
                .set("steps", scenarioSteps());

        // 예제 경로는 targetSuitability 0점이라 서술이 아무리 좋아도 임계를 넘으면 안 된다.
        ArrayNode methodSeed = objectMapper.createArrayNode();
        methodSeed.addObject()
                .put("fqn", "com.example.ossdoc.domain.run.service.RepoRunService.createRun")
                .put("classFqn", "com.example.ossdoc.domain.run.service.RepoRunService")
                .put("className", "RepoRunService")
                .put("methodName", "createRun")
                .put("summarySeed", "Creates a repository analysis run.")
                .put("signatureHint", "RepoRunCreateRequest -> RepoRunCreateResponse")
                .put("filePath", "src/main/java/example/RepoRunService.java")
                .put("startLine", 10)
                .put("endLine", 30);

        JsonNode card = support.buildCoreMethodCards(
                methodSeed, objectMapper.createArrayNode(), objectMapper.createArrayNode(), scenarios
        ).get(0);

        // 시나리오 서술은 살아 있고,
        assertThat(card.path("guideSlots").path("failureSymptom").asText()).contains("Invalid URL");
        // P1-3 지표도 함께 남아 있어야 한다. 빠지면 qualityGate가 1.0으로 간주해 필터가 죽는다.
        assertThat(card.path("guideQuality").path("targetSuitabilityScore").asDouble()).isEqualTo(0.0d);
        assertThat(card.path("guideQuality").path("meetsThreshold").asBoolean()).isFalse();
        assertThat(card.path("guideQuality").has("slotEvidenceConfidence")).isTrue();
    }

    private ObjectNode structureWithMethodSeed() {
        ObjectNode structure = objectMapper.createObjectNode();
        structure.set("overviewSeed", objectMapper.createObjectNode().put("repoName", "ossdoc"));
        ArrayNode methodSeed = structure.putArray("coreMethodSeed");
        methodSeed.addObject()
                .put("fqn", "com.example.ossdoc.domain.run.service.RepoRunService.createRun")
                .put("classFqn", "com.example.ossdoc.domain.run.service.RepoRunService")
                .put("className", "RepoRunService")
                .put("methodName", "createRun")
                .put("summarySeed", "Creates a repository analysis run.")
                .put("signatureHint", "RepoRunCreateRequest -> RepoRunCreateResponse")
                .put("filePath", "src/main/java/RepoRunService.java")
                .put("startLine", 10)
                .put("endLine", 30)
                .put("importance", 100);
        structure.set("methodFlowSeed", objectMapper.createArrayNode());
        return structure;
    }

    private ArrayNode scenarioSteps() {
        ArrayNode steps = objectMapper.createArrayNode();
        steps.addObject()
                .put("stepNo", 1)
                .put("description", "Check the repository URL and run the analysis request.")
                .put("precondition", "A repository URL is available.")
                .put("action", "Call RepoRunService.createRun with the repository URL.")
                .put("successSignal", "The run status changes to queued.")
                .put("failureSignal", "Invalid URL validation rejects the request.")
                .put("userAction", "Fix the URL and retry.")
                .put("dataHandled", "repoUrl")
                .put("evidenceInterpretation", "The method creates the run command from the request DTO.")
                .put("classFqn", "com.example.ossdoc.domain.run.service.RepoRunService")
                .put("methodFqn", "com.example.ossdoc.domain.run.service.RepoRunService.createRun")
                .put("confidence", 0.91)
                .set("evidenceLinks", objectMapper.createArrayNode()
                        .addObject()
                        .put("evidenceId", 7)
                        .put("filePath", "src/main/java/RepoRunService.java")
                        .put("lines", "10-30"));
        return steps;
    }

    @Test
    void normalizeScenarioSpecs_ignoresModelSuppliedFqnAndEvidenceLinks() throws Exception {
        // 프롬프트에서 뺐지만 모델이 습관적으로 넣어도 골격 값이 이겨야 한다.
        JsonNode raw = objectMapper.readTree("""
                {
                  "scenarios": [
                    {
                      "scenarioId": "SCN-001",
                      "steps": [
                        {
                          "stepNo": 1,
                          "description": "빌더에 필수 값을 넣는다.",
                          "classFqn": "com.evil.Wrong",
                          "methodFqn": "com.evil.Wrong.nope",
                          "evidenceLinks": [{"evidenceId": 29, "filePath": "fake/Path.java", "lines": "1-2"}]
                        }
                      ]
                    }
                  ]
                }
                """);

        JsonNode step = support.normalizeScenarioSpecs(raw, structureWithScenarioSeed())
                .path("scenarios").get(0).path("steps").get(0);

        assertThat(step.path("methodFqn").asText()).isEqualTo("com.acme.Builder.required");
        assertThat(step.path("classFqn").asText()).isEqualTo("com.acme.Builder");
        assertThat(step.path("evidenceLinks").get(0).path("filePath").asText())
                .isEqualTo("src/main/java/com/acme/Builder.java");
        // 모델 서술 자체는 살아남는다.
        assertThat(step.path("description").asText()).contains("필수 값");
    }

    @Test
    void normalizeScenarioSpecs_dropsScenariosOutsideTheSeedSkeleton() throws Exception {
        // 5차 실행에서 모델이 골격에 없는 클래스로 시나리오를 만들어 예산을 태웠다.
        JsonNode raw = objectMapper.readTree("""
                {
                  "scenarios": [
                    {
                      "scenarioId": "SCN-001",
                      "steps": [{"stepNo": 1, "description": "빌더를 구성한다."}]
                    },
                    {
                      "scenarioId": "SCN-002",
                      "steps": [{"stepNo": 1, "description": "파서를 호출한다."}]
                    },
                    {
                      "scenarioId": "SCN-999",
                      "steps": [{"stepNo": 1, "description": "골격에 없는 시나리오."}]
                    }
                  ]
                }
                """);

        JsonNode scenarios = support.normalizeScenarioSpecs(raw, structureWithScenarioSeed())
                .path("scenarios");

        assertThat(scenarios.size()).isEqualTo(2);
        assertThat(scenarios.get(0).path("scenarioId").asText()).isEqualTo("SCN-001");
        assertThat(scenarios.get(1).path("scenarioId").asText()).isEqualTo("SCN-002");
        // SCN-999는 골격에 없으므로 산출물에 남지 않는다.
        for (JsonNode scenario : scenarios) {
            assertThat(scenario.path("scenarioId").asText()).isNotEqualTo("SCN-999");
        }
    }
}
