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

    /**
     * <b>철칙: 서술을 가질 수 없는 항목을 분모에서 빼지 않는다.</b>
     *
     * <p>이 게이트는 두 축으로 나뉜다. 채점 대상이 전체보다 적으면 그 차이가 왜 생겼는지가
     * <b>같은 객체 안에</b> 반드시 함께 나와야 한다. 불편한 항목을 분모에서 덜어내
     * 좋아 보이게 만드는 것이 이 게이트가 고치려던 실패 그 자체이므로, 그 재발을
     * 주석이 아니라 테스트로 막는다. 나중에 누가 축2를 떨어뜨리면 여기서 걸린다.</p>
     */
    @Test
    void apiDocGate_neverHidesUnscorableItemsFromTheDenominator() throws Exception {
        // Alpha는 메서드 2개(서술 가능), Beta는 1개, Gamma는 hashCode뿐.
        JsonNode cards = objectMapper.readTree(
                "[{\"classFqn\":\"com.foo.Alpha\",\"fqn\":\"com.foo.Alpha.open\",\"methodName\":\"open\","
                        + "\"guideQuality\":{\"actionabilityScore\":100,\"slotCoverage\":1.0,\"evidenceCoverage\":1.0,"
                        + "\"forbiddenPhraseRate\":0.0,\"repetitionRate\":0.0,\"targetSuitabilityScore\":1.0}},"
                        + "{\"classFqn\":\"com.foo.Alpha\",\"fqn\":\"com.foo.Alpha.close\",\"methodName\":\"close\","
                        + "\"guideQuality\":{\"actionabilityScore\":100,\"slotCoverage\":1.0,\"evidenceCoverage\":1.0,"
                        + "\"forbiddenPhraseRate\":0.0,\"repetitionRate\":0.0,\"targetSuitabilityScore\":1.0}},"
                        + "{\"classFqn\":\"com.foo.Beta\",\"fqn\":\"com.foo.Beta.only\",\"methodName\":\"only\","
                        + "\"guideQuality\":{\"actionabilityScore\":55,\"slotCoverage\":0.0,\"evidenceCoverage\":1.0,"
                        + "\"forbiddenPhraseRate\":0.0,\"repetitionRate\":0.0,\"targetSuitabilityScore\":1.0}},"
                        + "{\"classFqn\":\"com.foo.Gamma\",\"fqn\":\"com.foo.Gamma.hashCode\",\"methodName\":\"hashCode\","
                        + "\"guideQuality\":{\"actionabilityScore\":55,\"slotCoverage\":0.0,\"evidenceCoverage\":1.0,"
                        + "\"forbiddenPhraseRate\":0.0,\"repetitionRate\":0.0,\"targetSuitabilityScore\":1.0}}]");

        ObjectNode gate = support.buildApiDocQualityGate(cards);

        // 전체 수는 절대 줄지 않는다.
        assertThat(gate.path("itemCount").asInt()).isEqualTo(4);
        assertThat(gate.path("scoredItemCount").asInt()).isEqualTo(2);

        // 줄어든 만큼이 반드시 이유와 함께 같은 객체 안에 남는다.
        assertThat(gate.path("unscorableItemCount").asInt())
                .isEqualTo(gate.path("itemCount").asInt() - gate.path("scoredItemCount").asInt())
                .isGreaterThan(0);
        assertThat(gate.path("unscorableReason").path("singleMethodClass").asInt()).isEqualTo(1);
        assertThat(gate.path("unscorableReason").path("objectMethod").asInt()).isEqualTo(1);
        assertThat(gate.path("narratabilityApplied").asBoolean()).isTrue();

        // 채점은 서술 가능한 2장만 대상으로 한다. 그래야 게이트가 초록에 도달할 수 있다.
        assertThat(gate.path("averageActionabilityScore").asDouble()).isEqualTo(100.0d);
        assertThat(gate.path("belowThresholdCount").asInt()).isZero();
        assertThat(gate.path("meetsThreshold").asBoolean()).isTrue();
    }

    /**
     * rules/cautions에는 서술 가능 판정을 적용하지 않는다.
     * 규칙에 "이 클래스에 메서드가 몇 개냐"를 묻는 것 자체가 의미 왜곡이다.
     */
    @Test
    void refinedRuleGate_doesNotApplyNarratability() throws Exception {
        JsonNode rules = objectMapper.readTree(
                "[{\"ruleId\":\"R-1\",\"guideQuality\":{\"actionabilityScore\":90,\"slotCoverage\":1.0,"
                        + "\"evidenceCoverage\":1.0,\"forbiddenPhraseRate\":0.0,\"repetitionRate\":0.0}}]");

        ObjectNode gate = support.buildRefinedRuleQualityGate(rules, objectMapper.createArrayNode());

        assertThat(gate.path("narratabilityApplied").asBoolean()).isFalse();
        assertThat(gate.path("scoredItemCount").asInt()).isEqualTo(gate.path("itemCount").asInt());
        assertThat(gate.path("unscorableItemCount").asInt()).isZero();
    }

    /**
     * 실측 산출물로 두 축을 검증한다.
     *
     * <p>축1은 움직이고(run A 15 → run B 2), 축2는 두 run에서 동일하다(10장).
     * 축2가 run과 무관하게 같다는 것이 축을 나눈 근거이므로 그 사실을 테스트로 고정한다.</p>
     *
     * <p>특히 {@code slotCoverageAvg}가 0.57과 1.0 두 값을 낸다는 것이 중요하다.
     * 이 지표가 1.0으로 <b>고정</b>돼 있던 것이 이 작업의 출발점이었다. 같은 코드가
     * 다른 입력에서 다른 값을 낸다는 것이 "이번 1.0은 고정이 아니라 채워진 것"의 증거다.</p>
     */
    @Test
    void apiDocGate_reproducesBothAxesFromStoredArtifacts() throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of(
                "C:", "data", "ossdoc", "run_20260815_94b1aaa5", "artifacts");
        java.nio.file.Path runA = base.resolve("llm_run10_runA").resolve("api_docs.json");
        java.nio.file.Path runB = base.resolve("llm_run11_runB").resolve("api_docs.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(runA) && java.nio.file.Files.isRegularFile(runB),
                "실측 산출물이 없는 환경입니다");

        ObjectNode gateA = apiGateOf(runA);
        ObjectNode gateB = apiGateOf(runB);

        // 축2 — 저장소의 성질이라 두 run에서 같다.
        for (ObjectNode gate : java.util.List.of(gateA, gateB)) {
            assertThat(gate.path("itemCount").asInt()).isEqualTo(40);
            assertThat(gate.path("scoredItemCount").asInt()).isEqualTo(30);
            assertThat(gate.path("unscorableItemCount").asInt()).isEqualTo(10);
            assertThat(gate.path("unscorableReason").path("singleMethodClass").asInt()).isEqualTo(8);
            assertThat(gate.path("unscorableReason").path("objectMethod").asInt()).isEqualTo(2);
        }

        // 축1 — max-scenarios 4 → 6의 효과. 기존 게이트에서는 25 → 12로 가려져 있었다.
        assertThat(gateA.path("belowThresholdCount").asInt()).isEqualTo(15);
        assertThat(gateB.path("belowThresholdCount").asInt()).isEqualTo(2);
        assertThat(gateA.path("averageActionabilityScore").asDouble()).isEqualTo(80.3d);
        assertThat(gateB.path("averageActionabilityScore").asDouble()).isEqualTo(99.8d);
        assertThat(gateA.path("minActionabilityScore").asInt()).isEqualTo(55);
        assertThat(gateB.path("minActionabilityScore").asInt()).isEqualTo(97);

        // slotCoverageAvg가 고정돼 있지 않다는 증거.
        assertThat(gateA.path("slotCoverageAvg").asDouble()).isEqualTo(0.57d);
        assertThat(gateB.path("slotCoverageAvg").asDouble()).isEqualTo(1.0d);

        // 둘 다 아직 초록은 아니다. run B는 채움말 2장만 남았다 — 도달 가능한 목표.
        assertThat(gateA.path("meetsThreshold").asBoolean()).isFalse();
        assertThat(gateB.path("meetsThreshold").asBoolean()).isFalse();
    }

    /**
     * 게이트의 "서술 가능" 판정이 골격 생성과 어긋나지 않는지 본다.
     *
     * <p>골격이 실제로 만든 시나리오의 클래스는 게이트가 판정한 narratable 집합의
     * <b>부분집합</b>이어야 한다({@code max-scenarios} 상한 때문에 등호는 아니다).
     * 어긋나면 게이트가 "구조적으로 불가능"이라고 말한 카드에 골격이 서술을 붙이고 있다는 뜻이다.</p>
     */
    @Test
    void gateNarratability_agreesWithSkeleton() throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of(
                "C:", "data", "ossdoc", "run_20260815_94b1aaa5", "artifacts", "llm_run11_runB");
        java.nio.file.Path cards = base.resolve("api_docs.json");
        java.nio.file.Path specs = base.resolve("scenario_specs.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(cards) && java.nio.file.Files.isRegularFile(specs),
                "실측 산출물이 없는 환경입니다");

        JsonNode coreMethods = objectMapper.readTree(java.nio.file.Files.readString(cards)).path("coreMethods");
        java.util.List<ScenarioNarratabilitySupport.MethodRef> refs = new java.util.ArrayList<>();
        for (JsonNode card : coreMethods) {
            String fqn = card.path("fqn").asText("");
            refs.add(new ScenarioNarratabilitySupport.MethodRef(
                    card.path("classFqn").asText(""), fqn,
                    fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn));
        }
        java.util.Set<String> narratable = ScenarioNarratabilitySupport.narratableClassFqns(refs);

        java.util.Set<String> skeletonClasses = new java.util.LinkedHashSet<>();
        for (JsonNode scenario : objectMapper.readTree(java.nio.file.Files.readString(specs)).path("scenarios")) {
            for (JsonNode step : scenario.path("steps")) {
                skeletonClasses.add(step.path("classFqn").asText(""));
            }
        }

        assertThat(skeletonClasses).isNotEmpty();
        assertThat(narratable).containsAll(skeletonClasses);
    }

    /**
     * relatedClass와 relatedMethod가 서로 다른 타입을 가리키는 caution은 메서드 카드에 붙이지 않는다.
     *
     * <p>실측 20개 중 6개가 모순이었고, {@code CAU-017}(relatedClass=Launcher,
     * relatedMethod=MediaType.create, 메시지는 Operator 이야기)이 {@code MediaType.create}
     * 카드의 failureSymptom에 전혀 다른 클래스의 설명을 붙였다.</p>
     */
    @Test
    void coreMethodCards_dropCautionWhenRelatedClassAndMethodContradict() throws Exception {
        JsonNode structure = objectMapper.readTree(
                "{\"coreMethodSeed\":[{\"fqn\":\"com.foo.Alpha.open\",\"classFqn\":\"com.foo.Alpha\","
                        + "\"methodName\":\"open\",\"signatureHint\":\"open()\",\"importance\":9,"
                        + "\"filePath\":\"src/Alpha.java\",\"startLine\":10,\"endLine\":12}]}");
        JsonNode cautions = objectMapper.readTree(
                "[{\"cautionId\":\"CAU-001\",\"relatedClass\":\"com.foo.Other\","
                        + "\"relatedMethod\":\"com.foo.Alpha.open\",\"message\":\"Other 이야기입니다.\"},"
                        + "{\"cautionId\":\"CAU-002\",\"relatedClass\":\"com.foo.Alpha\","
                        + "\"relatedMethod\":\"com.foo.Alpha.open\",\"message\":\"Alpha 이야기입니다.\"}]");

        ArrayNode cards = support.buildCoreMethodCards(
                structure.path("coreMethodSeed"),
                objectMapper.createArrayNode(),
                cautions,
                objectMapper.createObjectNode().set("scenarios", objectMapper.createArrayNode()));

        String rendered = cards.toString();
        // 같은 타입을 가리키는 caution은 그대로 쓰인다.
        assertThat(rendered).contains("Alpha 이야기입니다.");
        // 모순인 caution의 메시지는 이 카드의 근거로 주장되지 않는다.
        assertThat(rendered).doesNotContain("Other 이야기입니다.");
    }

    private ObjectNode apiGateOf(java.nio.file.Path path) throws Exception {
        JsonNode docs = objectMapper.readTree(java.nio.file.Files.readString(path));
        return support.buildApiDocQualityGate(docs.path("coreMethods"));
    }

    private ObjectNode gateOf(java.nio.file.Path path) throws Exception {
        JsonNode specs = objectMapper.readTree(java.nio.file.Files.readString(path));
        return support.buildScenarioSpecsQualityGate(specs.path("scenarios"), specs.path("overview"));
    }

    /**
     * 근거 없는 메서드는 빈 칸으로 남는다. 채움말로 메우지 않는다.
     *
     * <p>이전에는 메서드 이름 철자로 5칸을 전부 채우는 바람에 {@code getAncestors}에
     * "parse 실행이 끝난 결과 객체를 준비하고" 같은 설명이 붙었고, 그 탓에
     * slotCoverage가 항상 1.0이라 품질 게이트가 절대 떨어지지 않았다.</p>
     */
    @Test
    void buildGuide_leavesSlotsEmptyWhenNoEvidence() {
        ApiDocGuideSupport.GuideView guide = ApiDocGuideSupport.buildGuide(
                "com.example.Foo", "getAncestors", "com.example.Foo.getAncestors",
                "실행 결과에서 값 존재 여부를 확인하거나 값을 읽을 때 호출합니다.",   // inferMethodUsage 채움말
                java.util.List.of(),
                "src/main/java/com/example/Foo.java", 10, 40);

        assertThat(guide.slots().beforeCall()).isEmpty();
        assertThat(guide.slots().doCall()).isEmpty();          // summaryRaw가 채움말이라 비운다
        assertThat(guide.slots().successCheck()).isEmpty();
        assertThat(guide.slots().failureSymptom()).isEmpty();  // caution이 없다
        assertThat(guide.slots().nextAction()).isEmpty();
        assertThat(guide.summaryRaw()).isEmpty();
        assertThat(guide.narrative()).isEmpty();

        assertThat(guide.quality().slotCoverage()).isZero();
        // evidence 앵커 0.25만 남아 55점. threshold 70 미달이라 게이트가 잡는다.
        assertThat(guide.quality().actionabilityScore()).isEqualTo(55);
    }

    @Test
    void buildGuide_keepsEvidenceBackedSummaryAndCaution() {
        ApiDocGuideSupport.GuideView guide = ApiDocGuideSupport.buildGuide(
                "com.example.Foo", "parse", "com.example.Foo.parse",
                "입력 문자열을 분석해 MediaType 인스턴스를 만든다.",              // javadoc 유래 = 근거 있음
                java.util.List.of("형식이 잘못되면 예외가 발생한다."),                        // STEP① caution = 근거 있음
                "src/main/java/com/example/Foo.java", 10, 40);

        assertThat(guide.slots().doCall()).contains("MediaType 인스턴스");
        assertThat(guide.slots().failureSymptom()).contains("예외가 발생");
        // 이름 규칙 슬롯은 근거가 없으므로 여전히 비어 있다.
        assertThat(guide.slots().beforeCall()).isEmpty();
        assertThat(guide.quality().slotCoverage()).isEqualTo(0.4d);
        assertThat(guide.quality().forbiddenPhraseRate()).isZero();
    }

    // ------------------------------------------------------------------
    // 전파 회복: 날조된 시나리오 연결 제거
    // ------------------------------------------------------------------

    @Test
    void apiEntries_useRealScenarioIdInsteadOfHardcodedLiteral() {
        ArrayNode cards = objectMapper.createArrayNode();
        ObjectNode joined = cards.addObject();
        joined.put("fqn", "com.example.Foo.parse");
        joined.putObject("usageScenario").put("scenarioId", "SCN-003");
        ObjectNode unjoined = cards.addObject();
        unjoined.put("fqn", "com.example.Foo.other");

        ArrayNode entries = support.buildApiEntriesCompat(cards);

        // 예전에는 둘 다 "SCN-001" 리터럴을 받았다.
        assertThat(entries.get(0).path("relatedScenarios").get(0).asText()).isEqualTo("SCN-003");
        assertThat(entries.get(1).path("relatedScenarios")).isEmpty();
    }

    @Test
    void coreClasses_leaveRelatedScenariosEmptyWhenNoMatch() throws Exception {
        ArrayNode coreClasses = objectMapper.createArrayNode();
        coreClasses.addObject().put("fqn", "com.example.Matched");
        coreClasses.addObject().put("fqn", "com.example.Unrelated");

        JsonNode specs = objectMapper.readTree("""
                {"scenarios":[{"scenarioId":"SCN-001","steps":[
                  {"classFqn":"com.example.Matched","methodFqn":"com.example.Matched.run"}]}]}
                """);

        support.fillCoreClassRelatedScenarios(coreClasses, specs);

        assertThat(coreClasses.get(0).path("relatedScenarios").get(0).asText()).isEqualTo("SCN-001");
        // 예전에는 전체 시나리오 ID 앞 3개를 fallback으로 넣어
        // 실측 coreClasses 20개 중 16개가 관련 없는 연결을 달고 나갔다.
        assertThat(coreClasses.get(1).path("relatedScenarios")).isEmpty();
    }

    @Test
    void subsystemDocs_useMemberClassLinksInsteadOfSharingAllScenarios() throws Exception {
        JsonNode coreClasses = objectMapper.readTree("""
                [{"fqn":"com.example.a.Foo","packageName":"com.example.a","relatedScenarios":["SCN-001"]},
                 {"fqn":"com.example.b.Bar","packageName":"com.example.b","relatedScenarios":[]}]
                """);

        ArrayNode docs = support.buildSubsystemDocs(
                coreClasses, objectMapper.createObjectNode(), objectMapper.createObjectNode());

        // 서브시스템마다 실제로 닿는 시나리오만 달린다.
        // 예전에는 12개 서브시스템이 전부 같은 ID 3개를 갖고 있었다.
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).path("relatedScenarios").get(0).asText()).isEqualTo("SCN-001");
        assertThat(docs.get(1).path("relatedScenarios")).isEmpty();
    }

    /**
     * signatureHint의 반환·파라미터 타입을 설명해도 "골격 밖"으로 잡지 않는다.
     *
     * <p>골격 step은 호출 대상 클래스만 실어서, 반환값을 설명하는 정상 서술이
     * 구조적으로 오탐이 됐다. 실측에서 SCN-002의 9건이 전부 이 부류였다.</p>
     */
    @Test
    void offSkeletonMetric_allowsTypesFromSignatureHint() throws Exception {
        JsonNode structure = objectMapper.readTree("""
                {"scenarioSeed":[{"scenarioId":"SCN-001","steps":[{
                   "stepNo":1,
                   "classFqn":"org.junit.platform.engine.discovery.DiscoverySelectors",
                   "methodFqn":"org.junit.platform.engine.discovery.DiscoverySelectors.parse",
                   "signatureHint":"parse(DiscoverySelectorIdentifier) -> DiscoverySelector"
                 }]}]}
                """);
        JsonNode raw = objectMapper.readTree("""
                {"scenarios":[{"scenarioId":"SCN-001","steps":[{
                   "stepNo":1,
                   "description":"DiscoverySelectorIdentifier를 받아 DiscoverySelector를 돌려준다"
                 }]}]}
                """);

        JsonNode normalized = support.normalizeScenarioSpecs(raw, structure);

        // 로그로만 남는 지표라 직접 assert할 값은 없다.
        // 서술이 보존되고 예외 없이 통과하는지만 고정하고,
        // 오탐 여부는 run 로그의 "방황 이동" 항목으로 확인한다.
        assertThat(normalized.path("scenarios").get(0).path("steps").get(0).path("description").asText())
                .contains("DiscoverySelector");
    }

    @Test
    void buildApiFlowSummary_exposesSemanticEdgeTrustStats() throws Exception {
        JsonNode traces = objectMapper.readTree("""
                [{
                  "entryName": "OrderController",
                  "entryQualifiedName": "org.acme.OrderController",
                  "exposure": "PRIMARY",
                  "reachableNodes": [
                    {"name":"OrderController","bfsDepth":0},
                    {"name":"OrderService","bfsDepth":1}
                  ],
                  "reachableEdges": [
                    {
                      "confidence": 0.93,
                      "resolution": "RESOLVED",
                      "defaultVisible": true
                    },
                    {
                      "confidence": 0.61,
                      "resolution": "PARTIAL",
                      "defaultVisible": false
                    }
                  ],
                  "maxDepth": 1,
                  "truncated": false
                }]
                """);

        JsonNode summary = support.buildApiFlowSummary(traces, 10).get(0);

        assertThat(summary.path("trustedEdgeCount").asInt()).isEqualTo(1);
        assertThat(summary.path("inferredEdgeCount").asInt()).isEqualTo(1);
        assertThat(summary.path("minEdgeConfidence").asDouble()).isEqualTo(0.61d);
    }

}
