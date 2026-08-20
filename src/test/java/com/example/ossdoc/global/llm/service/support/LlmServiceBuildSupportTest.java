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

    // ------------------------------------------------------------------
    // 게이트 실질화 회귀 테스트
    // ------------------------------------------------------------------

    /**
     * 이 프로젝트에서 가장 오래 숨어 있던 버그를 고정한다.
     *
     * <p>{@code buildApiDocQualityGate}가 {@code targetSuitabilityScore} 부재를
     * {@code asDouble(1.0d)}로 읽어 "적합"으로 간주했고, 그 필드를 생산하지
     * 않는 rules/cautions 경로가 통째로 P1-3 필터를 우회하고 있었다.
     * 기존 테스트는 {@code buildCoreMethodCards} 경로만 보아 이걸 못 잡았다.</p>
     */
    @Test
    void qualityGate_doesNotTreatMissingTargetSuitabilityAsPass() {
        ArrayNode items = objectMapper.createArrayNode();
        ObjectNode item = items.addObject();
        item.put("actionabilityScore", 100);
        ObjectNode quality = item.putObject("guideQuality");
        quality.put("actionabilityScore", 100);
        quality.put("slotCoverage", 1.0d);
        quality.put("evidenceCoverage", 1.0d);
        quality.put("forbiddenPhraseRate", 0.0d);
        quality.put("repetitionRate", 0.0d);
        // targetSuitabilityScore를 일부러 넣지 않는다 = attachGuideBundle 경로의 모양

        ObjectNode gate = support.buildApiDocQualityGate(items, true);

        assertThat(gate.path("targetSuitabilityMissingCount").asInt()).isEqualTo(1);
        assertThat(gate.path("belowThresholdCount").asInt()).isEqualTo(1);
        assertThat(gate.path("meetsThreshold").asBoolean()).isFalse();
        // 미측정을 1.0으로 셔서 평균을 올리지 않는다.
        assertThat(gate.path("targetSuitabilityAvg").asDouble()).isEqualTo(0.0d);
    }

    @Test
    void qualityGate_skipsTargetSuitabilityForRules() {
        ArrayNode rules = objectMapper.createArrayNode();
        ObjectNode rule = rules.addObject();
        rule.put("actionabilityScore", 100);
        ObjectNode quality = rule.putObject("guideQuality");
        quality.put("actionabilityScore", 100);
        quality.put("forbiddenPhraseRate", 0.0d);

        ObjectNode gate = support.buildRefinedRuleQualityGate(rules, objectMapper.createArrayNode());

        // 규칙은 메서드 대상이 아니므로 합성/예제 판정을 적용하지 않고,
        // 그 사실을 산출물에 명시한다.
        assertThat(gate.path("targetSuitabilityApplied").asBoolean()).isFalse();
        assertThat(gate.path("belowThresholdCount").asInt()).isZero();
        assertThat(gate.path("itemCount").asInt()).isEqualTo(1);
    }

    @Test
    void qualityGate_failsWhenSlotContainsFiller() {
        ArrayNode items = objectMapper.createArrayNode();
        ObjectNode item = items.addObject();
        item.put("actionabilityScore", 97);
        ObjectNode quality = item.putObject("guideQuality");
        quality.put("actionabilityScore", 97);
        quality.put("forbiddenPhraseRate", 0.2d);
        quality.put("targetSuitabilityScore", 1.0d);

        ObjectNode gate = support.buildApiDocQualityGate(items, true);

        // 점수는 threshold(70)를 넘지만 채움말이 섞여 있으므로 통과시키지 않는다.
        assertThat(gate.path("belowThresholdCount").asInt()).isEqualTo(1);
        assertThat(gate.path("meetsThreshold").asBoolean()).isFalse();
    }

    @Test
    void scenarioGate_reportsNarrativeCoverageAndFiller() throws Exception {
        JsonNode scenarios = objectMapper.readTree("""
                [{"scenarioId":"SCN-001","steps":[
                  {"stepNo":1,"description":"문자열을 파싱한다","precondition":"입력이 준비된다",
                   "action":"parse를 호출한다","successSignal":"객체가 반환된다",
                   "failureSignal":"예외가 발생한다","userAction":"입력을 고친다",
                   "dataHandled":"문자열","evidenceInterpretation":"MediaType 의 핵심 기능을 연결할 때 호출합니다."},
                  {"stepNo":2,"description":"값을 읽는다"}
                ]}]
                """);

        ObjectNode gate = support.buildScenarioSpecsQualityGate(scenarios, objectMapper.createObjectNode());

        assertThat(gate.path("stepCount").asInt()).isEqualTo(2);
        assertThat(gate.path("narrativeFieldTotal").asInt()).isEqualTo(18);   // step 2개 x 9필드
        assertThat(gate.path("narrativeFieldFilled").asInt()).isEqualTo(9);
        // 채운 9칸 중 1칸이 채움말이다. 채운 것과 제대로 채운 것은 다르다.
        assertThat(gate.path("fillerFieldCount").asInt()).isEqualTo(1);
        assertThat(gate.path("meetsThreshold").asBoolean()).isFalse();
    }

    /**
     * 실측 산출물로 게이트를 검증한다. <b>이 프로젝트에서 가장 강한 검증이다.</b>
     *
     * <p>같은 저장소(junit-framework)를 SINGLE 모드와 PER_SCENARIO 모드로 각각 돌린
     * 실제 산출물이 디스크에 남아 있다(36/88, 85/88). 새 게이트가 그 두 값을
     * 재현하는지를 LLM 호출 없이 확인한다. 재현하지 못하면 계기판을 믿을 수 없다.</p>
     *
     * <p>산출물이 없는 환경(CI 등)에서는 건너뛴다. 실측 고정 입력은 개발 머신에만
     * 존재하므로 이 테스트를 필수로 두면 CI가 깨진다.</p>
     */
    @Test
    void scenarioGate_reproducesMeasuredCoverageFromStoredArtifacts() throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of(
                "C:", "data", "ossdoc", "run_20260815_94b1aaa5", "artifacts");
        java.nio.file.Path single = base.resolve("llm_run8_baseline").resolve("scenario_specs.json");
        java.nio.file.Path perScenario = base.resolve("llm_run9_perscenario").resolve("scenario_specs.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(single) && java.nio.file.Files.isRegularFile(perScenario),
                "실측 산출물이 없는 환경입니다");

        ObjectNode singleGate = gateOf(single);
        ObjectNode perScenarioGate = gateOf(perScenario);

        assertThat(singleGate.path("stepCount").asInt()).isEqualTo(11);
        assertThat(perScenarioGate.path("stepCount").asInt()).isEqualTo(11);

        // 서술 8필드 + description = 9. 11 step x 9 = 99칸이지만 실측 보고는
        // description을 뺄 8필드 기준 88칸이었다. 둘 다 같은 분모를 쓰므로 비율로 비교한다.
        double singleCoverage = singleGate.path("narrativeFieldCoverage").asDouble();
        double perScenarioCoverage = perScenarioGate.path("narrativeFieldCoverage").asDouble();

        assertThat(singleCoverage).isLessThan(0.6d);
        assertThat(perScenarioCoverage).isGreaterThan(0.9d);
        assertThat(perScenarioCoverage - singleCoverage).isGreaterThan(0.4d);

        // 게이트가 두 상태를 실제로 가른다.
        assertThat(singleGate.path("meetsThreshold").asBoolean()).isFalse();

        // PER_SCENARIO도 채움말 1칸 때문에 통과하지 못한다 — 채운 것과
        // 제대로 채운 것을 가르는 게 fillerFieldCount의 존재 이유다.
        assertThat(perScenarioGate.path("fillerFieldCount").asInt()).isEqualTo(1);
    }

    private ObjectNode gateOf(java.nio.file.Path path) throws Exception {
        JsonNode specs = objectMapper.readTree(java.nio.file.Files.readString(path));
        return support.buildScenarioSpecsQualityGate(specs.path("scenarios"), specs.path("overview"));
    }
}
