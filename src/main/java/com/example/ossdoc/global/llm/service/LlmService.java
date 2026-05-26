package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.config.LlmConfig;
import com.example.ossdoc.global.llm.dto.json.LlmResult;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.dto.response.LlmResponse;
import com.example.ossdoc.global.llm.entity.LlmScenarioCache;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * LLM 파이프라인 서비스.
 * - 출력 계약(8개)을 기준으로 결과를 구성한다.
 * - 실패가 잦은 구간은 결정론 fallback으로 안정화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final String ARTIFACT_SCHEMA_VERSION = "1.0";
    private static final String SCENARIO_PROMPT_VERSION = "scenario-spec-v4";

    private static final String STEP1_REFINED_RULES = "Step 1/5 - refined_rules";
    private static final String STEP2_SCENARIO_SPECS = "Step 2/5 - scenario_specs";
    private static final String STEP3_SUBSYSTEM_SUMMARIES = "Step 3/5 - subsystem_summaries";
    private static final String STEP4_API_DOCS = "Step 4/5 - api_docs";
    private static final String STEP5_FILE_TREE_DOCS = "Step 5/5 - file_tree_docs";

    private static final String PATH_REFINED_RULES = "llm/refined_rules.json";
    private static final String PATH_SCENARIO_SPECS = "llm/scenario_specs.json";
    private static final String PATH_SUBSYSTEM_SUMMARIES = "llm/subsystem_summaries.json";
    private static final String PATH_API_DOCS = "llm/api_docs.json";
    private static final String PATH_FILE_TREE_DOCS = "llm/file_tree_docs.json";

    private static final int MAX_CLAUDE_RETRY_ATTEMPTS = 2;
    private static final long BASE_RETRY_DELAY_MILLIS = 1500L;
    private static final long MAX_RETRY_DELAY_MILLIS = 12000L;

    private static final int MAX_CAUTIONS = 12;
    private static final int MAX_SCENARIOS = 2;
    private static final int MAX_STEPS_PER_SCENARIO = 4;
    private static final int MAX_CORE_CLASSES = 10;
    private static final int MAX_CORE_METHODS = 18;
    private static final int MAX_METHOD_FLOW = 8;
    private static final int MAX_EVIDENCE_LINKS = 3;
    private static final int MAX_API_ENTRY_OUTPUT = 14;
    private static final int MAX_METHOD_DESCRIPTION_PREVIEW = 140;

    private static final int TOKENS_CAUTIONS = 4000;
    private static final int TOKENS_SCENARIOS = 10000;
    private static final int CONTEXT_LIMIT_CAUTIONS_COMPACT = 14000;
    private static final int CONTEXT_LIMIT_SCENARIOS_COMPACT = 22000;

    private static final String MAIN_JAVA_MARKER = "/src/main/java/";
    private static final String MAIN_KOTLIN_MARKER = "/src/main/kotlin/";
    private static final String TEST_MARKER = "/src/test/";
    private static final String TARGET_MARKER = "/target/";

    private static final String KOREAN_POLICY = """
            - 자연어 출력은 반드시 한국어로 작성한다.
            - JSON만 출력하고 마크다운/코드블록은 금지한다.
            - 근거가 없으면 '추정'으로 표기하거나 해당 문장을 제거한다.
            - 규칙 ID 자체를 설명 중심으로 그대로 노출하지 말고 사용자 주의사항으로 변환한다.
            """;

    private static final String PROMPT_CAUTIONS = """
            역할: 구조 시드를 바탕으로 "사용자 주의사항/예외"를 작성한다.
            목표: 코드 내부 규칙명을 그대로 복제하지 말고, 실제 사용자가 실수하기 쉬운 포인트를 안내한다.
            제약:
            1) cautions는 최대 %d개
            2) 각 항목은 title(짧게), message(1~2문장), when(언제 주의할지) 포함
            3) relatedClass/relatedMethod/evidenceIds를 가능한 범위에서 채운다.
            4) evidenceIds는 시드에 제공된 "ev_" 형식 문자열 ID를 그대로 사용한다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "relatedClass":"pkg.Type","relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    private static final String PROMPT_CAUTIONS_COMPACT = """
            역할: 주의사항/예외를 짧게 요약한다.
            제약:
            1) cautions 최대 %d개
            2) message는 100자 이내
            3) 중복 항목 제거
            4) evidenceIds는 시드에 제공된 "ev_" 형식 문자열 ID를 그대로 사용한다.
            출력 스키마(JSON):
            {"cautions":[{"cautionId":"CAU-001","title":"string","message":"string","when":"string",
            "relatedClass":"pkg.Type","relatedMethod":"pkg.Type.method","evidenceIds":["ev_abc123"],"confidence":0.0}]}
            """;

    private static final String PROMPT_SCENARIOS = """
            역할: 오픈소스 첫 사용자를 위한 "시작 가이드 시나리오"를 작성한다.
            반드시 아래 출력 계약을 따른다.
            1) overview: 문제/목적/적합한 사용 상황/핵심 기능/시작 가이드
            2) scenarios: 단계별 흐름(각 단계는 classFqn, methodFqn, evidenceLinks 연결)
            3) methodFlow: 실제 호출 순서(order, title, methodFqn)
            제약:
            - scenarios 최대 %d개
            - scenario 당 steps 최대 %d개
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string","startGuide":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string",
            "steps":[{"stepNo":1,"description":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","methodFqn":"pkg.Type.method"}]}
            """;

    private static final String PROMPT_SCENARIOS_COMPACT = """
            역할: 시작 시나리오를 compact 모드로 작성한다.
            제약:
            - scenarios 최대 %d개
            - 각 step 설명은 1문장
            - 근거 링크가 없는 문장은 최소화
            출력 스키마(JSON):
            {"overview":{"project":"string","purpose":"string","fitSituation":"string","coreFeatures":"string","startGuide":"string"},
            "scenarios":[{"scenarioId":"SCN-001","title":"string","intent":"string",
            "steps":[{"stepNo":1,"description":"string","classFqn":"pkg.Type","methodFqn":"pkg.Type.method",
            "evidenceLinks":[{"evidenceId":1,"filePath":"path","lines":"10-20"}]}]}],
            "methodFlow":[{"order":1,"title":"string","description":"string","methodFqn":"pkg.Type.method"}]}
            """;

    private final RestClient claudeRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final ArtifactService artifactService;
    private final RepoRunRepository repoRunRepository;
    private final LlmInputAssemblerService llmInputAssemblerService;
    private final LlmScenarioCacheService llmScenarioCacheService;

    /**
     * LLM 정제 실행.
     */
    @Transactional
    public LlmResponse refine(LlmRequest request) {
        log.info("[LlmService] Refinement start. runId={}", request.getRunId());

        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new LlmException(LlmErrorCode.RUN_NOT_FOUND));

        LlmInputAssemblerService.LlmContextBundle bundle = llmInputAssemblerService.assemble(request);
        JsonNode structure = objectMapper.valueToTree(bundle.structureEngineOutput());
        List<LlmRequest.EvidenceSnippet> evidence = bundle.evidenceBundle() == null
                ? List.of()
                : bundle.evidenceBundle();

        JsonNode refinedRules = generateCautions(structure, evidence);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_REFINED_RULES, ARTIFACT_SCHEMA_VERSION, PATH_REFINED_RULES, refinedRules
        );

        JsonNode scenarioSpecs = generateScenarioSpecs(structure, refinedRules, evidence);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_SCENARIO_SPECS, ARTIFACT_SCHEMA_VERSION, PATH_SCENARIO_SPECS, scenarioSpecs
        );
        LlmScenarioCache scenarioCache = llmScenarioCacheService.upsertScenarioCache(
                run,
                scenarioSpecs,
                resolvePrimaryModel(),
                SCENARIO_PROMPT_VERSION
        );

        JsonNode subsystemSummaries = buildSubsystemSummaries(structure, scenarioSpecs, refinedRules);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_SUBSYSTEM_SUMMARIES, ARTIFACT_SCHEMA_VERSION, PATH_SUBSYSTEM_SUMMARIES, subsystemSummaries
        );

        JsonNode apiDocs = buildApiDocs(structure, scenarioSpecs, refinedRules);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_API_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_API_DOCS, apiDocs
        );

        JsonNode fileTreeDocs = buildFileTreeDocs(structure, apiDocs);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_FILE_TREE_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_FILE_TREE_DOCS, fileTreeDocs
        );

        log.info("[LlmService] Refinement complete. runId={}", request.getRunId());

        LlmResult result = LlmResult.builder()
                .runId(request.getRunId())
                .refinedRules(refinedRules)
                .scenarioSpecs(scenarioSpecs)
                .subsystemSummaries(subsystemSummaries)
                .apiDocs(apiDocs)
                .fileTreeDocs(fileTreeDocs)
                .scenarioCacheId(scenarioCache.getCacheId())
                .build();

        return new LlmResponse(request.getRunId(), result);
    }

    /**
     * Step 1: 규칙 후보를 사용자 주의사항으로 변환.
     */
    private JsonNode generateCautions(JsonNode structure, List<LlmRequest.EvidenceSnippet> evidence) {
        String context = buildCautionContext(structure, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP1_REFINED_RULES, context.length());

        JsonNode cautions = generateWithRetryPlan(
                STEP1_REFINED_RULES,
                applyLanguagePolicy(String.format(PROMPT_CAUTIONS, MAX_CAUTIONS)),
                applyLanguagePolicy(String.format(PROMPT_CAUTIONS_COMPACT, Math.min(8, MAX_CAUTIONS))),
                context,
                TOKENS_CAUTIONS,
                CONTEXT_LIMIT_CAUTIONS_COMPACT,
                raw -> normalizeCautions(raw, structure),
                () -> fallbackCautions(structure)
        );

        // 기존 클라이언트 호환을 위해 rules 키도 함께 제공한다.
        ObjectNode out = objectMapper.createObjectNode();
        out.set("cautions", cautions.path("cautions"));
        out.set("rules", toRulesFromCautions(cautions.path("cautions")));
        out.put("cautionCount", cautions.path("cautions").size());
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 2: 프로젝트 개요 + 대표 시나리오 + 메서드 사용 순서 생성.
     */
    private JsonNode generateScenarioSpecs(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        String context = buildScenarioContext(structure, refinedRules, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP2_SCENARIO_SPECS, context.length());

        return generateWithRetryPlan(
                STEP2_SCENARIO_SPECS,
                applyLanguagePolicy(String.format(PROMPT_SCENARIOS, MAX_SCENARIOS, MAX_STEPS_PER_SCENARIO)),
                applyLanguagePolicy(String.format(PROMPT_SCENARIOS_COMPACT, Math.min(3, MAX_SCENARIOS))),
                context,
                TOKENS_SCENARIOS,
                CONTEXT_LIMIT_SCENARIOS_COMPACT,
                raw -> normalizeScenarioSpecs(raw, structure),
                () -> fallbackScenarioSpecs(structure, refinedRules)
        );
    }

    /**
     * Step 3: 핵심 클래스/서브시스템/확장 포인트를 결정론적으로 정리한다.
     * 대형 JSON 생성을 LLM에 위임하지 않아 실패율과 비용을 낮춘다.
     */
    private JsonNode buildSubsystemSummaries(JsonNode structure, JsonNode scenarioSpecs, JsonNode refinedRules) {
        log.info("[LlmService] {} (deterministic)", STEP3_SUBSYSTEM_SUMMARIES);

        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode coreClasses = buildCoreClassDocs(structure.path("coreClassSeed"), structure.path("coreMethodSeed"));
        fillCoreClassRelatedScenarios(coreClasses, scenarioSpecs);
        ArrayNode extensionPoints = buildExtensionPointDocs(structure.path("extensionSeed"));
        ArrayNode subsystems = buildSubsystemDocs(coreClasses, scenarioSpecs, refinedRules);

        out.set("coreClasses", coreClasses);
        out.set("extensionPoints", extensionPoints);
        out.set("subsystems", subsystems);
        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 4: 핵심 메서드 카드와 사용 순서를 결정론적으로 생성한다.
     */
    private JsonNode buildApiDocs(JsonNode structure, JsonNode scenarioSpecs, JsonNode refinedRules) {
        log.info("[LlmService] {} (deterministic)", STEP4_API_DOCS);

        ArrayNode coreMethods = buildCoreMethodCards(
                structure.path("coreMethodSeed"),
                structure.path("methodFlowSeed"),
                refinedRules.path("cautions")
        );
        ArrayNode methodUsageOrder = buildMethodUsageOrder(
                structure.path("methodFlowSeed"),
                scenarioSpecs.path("scenarios")
        );
        ArrayNode apiEntries = buildApiEntriesCompat(coreMethods);

        ObjectNode out = objectMapper.createObjectNode();
        out.set("coreMethods", coreMethods);
        out.set("methodUsageOrder", methodUsageOrder);
        out.set("apiEntries", apiEntries);
        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 5: 디렉터리/근거 위치 출력.
     * coreMethods는 api_docs의 방법 카드를 복사하지 않고 coreMethodSeed에서 파일 위치 뷰를 독립 생성한다.
     */
    private JsonNode buildFileTreeDocs(JsonNode structure, JsonNode apiDocs) {
        log.info("[LlmService] {} (deterministic)", STEP5_FILE_TREE_DOCS);

        Map<String, String> classSourceMap = buildClassSourceMap(structure.path("coreClassSeed"));

        ObjectNode out = objectMapper.createObjectNode();
        out.set("directories", structure.path("directories").deepCopy());
        out.set("evidenceLocations", structure.path("evidenceIndex").deepCopy());
        out.set("coreMethods", buildFileLocationMethods(structure.path("coreMethodSeed"), classSourceMap));
        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * coreClassSeed에서 classFqn → sourceFile 인덱스를 구성한다.
     * coreMethodSeed의 filePath가 비어 있을 때 소속 클래스의 파일 경로를 fallback으로 사용한다.
     */
    private Map<String, String> buildClassSourceMap(JsonNode coreClassSeed) {
        Map<String, String> map = new HashMap<>();
        if (!coreClassSeed.isArray()) {
            return map;
        }
        for (JsonNode cls : coreClassSeed) {
            String fqn = cls.path("fqn").asText("").trim();
            String filePath = cls.path("filePath").asText("").trim();
            if (!fqn.isBlank() && !filePath.isBlank() && isUserFacingSourcePath(filePath)) {
                map.put(fqn, filePath);
            }
        }
        return map;
    }

    /**
     * file_tree_docs 전용 coreMethods — 파일 탐색 시 소스 위치를 빠르게 찾기 위한 경량 뷰.
     * api_docs의 방법 카드(whatItDoes, inputs, returns 등)와 중복되지 않도록 위치 정보만 포함한다.
     * filePath가 없으면 classFqn으로 classSourceMap에서 역추적한다.
     */
    private ArrayNode buildFileLocationMethods(JsonNode methodSeed, Map<String, String> classSourceMap) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!methodSeed.isArray()) {
            return out;
        }
        for (int i = 0; i < methodSeed.size() && out.size() < MAX_CORE_METHODS; i++) {
            JsonNode seed = methodSeed.get(i);
            String classFqn = seed.path("classFqn").asText("").trim();
            String filePath = seed.path("filePath").asText("").trim();
            if (filePath.isBlank()) {
                filePath = classSourceMap.getOrDefault(classFqn, "");
            }
            if (filePath.isBlank() || !isUserFacingSourcePath(filePath)) {
                continue;
            }
            ObjectNode item = out.addObject();
            item.put("fqn", seed.path("fqn").asText(""));
            item.put("methodName", seed.path("methodName").asText(""));
            item.put("classFqn", classFqn);
            item.put("filePath", filePath);
            if (seed.path("startLine").canConvertToInt()) {
                item.put("startLine", seed.path("startLine").asInt());
            }
            if (seed.path("endLine").canConvertToInt()) {
                item.put("endLine", seed.path("endLine").asInt());
            }
            item.put("summary", shortenText(normalizeSentence(seed.path("summarySeed").asText("")), 120));
            item.put("importance", seed.path("importance").asInt(0));
        }
        return out;
    }

    private JsonNode generateWithRetryPlan(
            String stepName,
            String normalPrompt,
            String compactPrompt,
            String context,
            int maxTokens,
            int compactContextLimit,
            Function<JsonNode, JsonNode> normalizer,
            Supplier<JsonNode> fallbackSupplier
    ) {
        try {
            JsonNode raw = callClaudeWithHaikuFallback(stepName, normalPrompt, context, maxTokens);
            return normalizer.apply(raw);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn("[LlmService] {} parse failed. retrying compact mode.", stepName);
        }

        String compactContext = shortenText(context, compactContextLimit);
        try {
            JsonNode raw = callClaudeWithHaikuFallback(stepName + " compact", compactPrompt, compactContext, maxTokens);
            return normalizer.apply(raw);
        } catch (LlmException secondFailure) {
            if (!isResponseParseFailed(secondFailure)) {
                throw secondFailure;
            }
            log.warn("[LlmService] {} fallback applied.", stepName);
            return fallbackSupplier.get();
        }
    }

    private JsonNode normalizeCautions(JsonNode raw, JsonNode structure) {
        JsonNode cautionsRaw = extractArrayByKey(raw, "cautions");
        if (cautionsRaw == null || !cautionsRaw.isArray()) {
            cautionsRaw = extractArrayByKey(raw, "rules");
        }
        if (cautionsRaw == null || !cautionsRaw.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ArrayNode cautions = objectMapper.createArrayNode();
        Set<String> dedupe = new HashSet<>();

        Map<String, ObjectNode> seedSmByCautionId = new HashMap<>();
        JsonNode seedCautions = structure.path("cautionSeed").path("cautions");
        if (seedCautions.isArray()) {
            for (JsonNode sc : seedCautions) {
                String cid  = sc.path("cautionId").asText("");
                String cond = sc.path("condition").asText("");
                String act  = sc.path("action").asText("");
                if (!cid.isBlank() && (!cond.isBlank() || !act.isBlank())) {
                    ObjectNode sm = objectMapper.createObjectNode();
                    if (!cond.isBlank()) sm.put("condition", cond);
                    if (!act.isBlank())  sm.put("action", act);
                    seedSmByCautionId.put(cid, sm);
                }
            }
        }

        for (int i = 0; i < cautionsRaw.size() && cautions.size() < MAX_CAUTIONS; i++) {
            JsonNode rawItem = cautionsRaw.get(i);
            if (!rawItem.isObject()) {
                continue;
            }

            String title = shortenText(firstNonBlank(
                    rawItem.path("title").asText(""),
                    rawItem.path("name").asText("")
            ), 70);
            String message = shortenText(firstNonBlank(
                    rawItem.path("message").asText(""),
                    rawItem.path("description").asText("")
            ), 180);

            if (title.isBlank() || message.isBlank()) {
                continue;
            }
            String key = (title + "|" + message).toLowerCase(Locale.ROOT);
            if (!dedupe.add(key)) {
                continue;
            }

            ObjectNode item = cautions.addObject();
            item.put("cautionId", String.format("CAU-%03d", cautions.size()));
            item.put("title", title);
            item.put("message", message);
            item.put("when", shortenText(rawItem.path("when").asText("호출 전 입력값과 호출 순서를 점검할 때"), 100));
            putIfText(item, "relatedClass", rawItem.path("relatedClass").asText(""));
            putIfText(item, "relatedMethod", rawItem.path("relatedMethod").asText(""));
            item.set("evidenceIds", limitEvidenceIdArray(rawItem.path("evidenceIds"), MAX_EVIDENCE_LINKS));
            item.put("confidence", normalizeConfidence(rawItem.path("confidence").asDouble(0.75d)));
            String rawCautionId = rawItem.path("cautionId").asText("");
            if (!rawCautionId.isBlank()) {
                ObjectNode seedSm = seedSmByCautionId.get(rawCautionId);
                if (seedSm != null) {
                    item.set("summary", seedSm);
                }
            }
        }

        if (cautions.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("cautions", cautions);
        return out;
    }

    private JsonNode normalizeScenarioSpecs(JsonNode raw, JsonNode structure) {
        JsonNode scenariosRaw = extractArrayByKey(raw, "scenarios");
        if (scenariosRaw == null || !scenariosRaw.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(raw.path("overview"), structure));
        out.set("methodFlow", normalizeMethodFlow(raw.path("methodFlow"), structure.path("methodFlowSeed")));
        Map<String, JsonNode> methodSeedByFqn = indexMethodSeedByFqn(structure.path("coreMethodSeed"));
        ArrayNode flowSeed = out.path("methodFlow").isArray()
                ? (ArrayNode) out.path("methodFlow")
                : objectMapper.createArrayNode();

        ArrayNode scenarios = out.putArray("scenarios");
        for (int i = 0; i < scenariosRaw.size() && scenarios.size() < MAX_SCENARIOS; i++) {
            JsonNode rawScenario = scenariosRaw.get(i);
            if (!rawScenario.isObject()) {
                continue;
            }

            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenarioId", firstNonBlank(rawScenario.path("scenarioId").asText(""), String.format("SCN-%03d", scenarios.size())));
            scenario.put("title", shortenText(rawScenario.path("title").asText("대표 사용 시나리오"), 90));
            scenario.put("intent", shortenText(rawScenario.path("intent").asText("핵심 API를 순서대로 호출해 기능을 완성한다."), 160));

            ArrayNode steps = scenario.putArray("steps");
            JsonNode rawSteps = rawScenario.path("steps");
            if (rawSteps.isArray()) {
                for (int s = 0; s < rawSteps.size() && steps.size() < MAX_STEPS_PER_SCENARIO; s++) {
                    JsonNode rawStep = rawSteps.get(s);
                    if (!rawStep.isObject()) {
                        continue;
                    }
                    JsonNode flowStep = s < flowSeed.size() ? flowSeed.get(s) : NullNode.getInstance();
                    ObjectNode step = steps.addObject();
                    step.put("stepNo", rawStep.path("stepNo").asInt(s + 1));
                    step.put("description", shortenText(rawStep.path("description").asText("핵심 메서드를 호출한다."), 160));
                    String methodFqn = firstNonBlank(
                            rawStep.path("methodFqn").asText(""),
                            flowStep.path("methodFqn").asText("")
                    );
                    String classFqn = firstNonBlank(
                            rawStep.path("classFqn").asText(""),
                            flowStep.path("classFqn").asText(""),
                            ownerFromMethodFqn(methodFqn)
                    );
                    putIfText(step, "classFqn", classFqn);
                    putIfText(step, "methodFqn", methodFqn);

                    ArrayNode evidenceLinks = normalizeEvidenceLinks(rawStep.path("evidenceLinks"), MAX_EVIDENCE_LINKS);
                    if (evidenceLinks.isEmpty()) {
                        evidenceLinks = evidenceLinksFromSeed(flowStep);
                    }
                    if (evidenceLinks.isEmpty() && !methodFqn.isBlank()) {
                        evidenceLinks = evidenceLinksFromSeed(methodSeedByFqn.getOrDefault(methodFqn, NullNode.getInstance()));
                    }
                    step.set("evidenceLinks", evidenceLinks);
                }
            }

            if (steps.isEmpty()) {
                ObjectNode step = steps.addObject();
                step.put("stepNo", 1);
                step.put("description", "핵심 API 호출 순서를 따라 실행한다.");
                JsonNode firstFlow = flowSeed.isEmpty() ? NullNode.getInstance() : flowSeed.get(0);
                String methodFqn = firstFlow.path("methodFqn").asText("");
                putIfText(step, "methodFqn", methodFqn);
                putIfText(step, "classFqn", firstFlow.path("classFqn").asText(""));
                step.set("evidenceLinks", evidenceLinksFromSeed(firstFlow));
            }
        }

        if (scenarios.isEmpty()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return out;
    }

    private JsonNode fallbackCautions(JsonNode structure) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode cautions = out.putArray("cautions");
        JsonNode seed = structure.path("cautionSeed").path("cautions");

        if (seed.isArray()) {
            for (int i = 0; i < seed.size() && cautions.size() < MAX_CAUTIONS; i++) {
                JsonNode item = seed.get(i);
                ObjectNode caution = cautions.addObject();
                caution.put("cautionId", String.format("CAU-%03d", cautions.size()));
                caution.put("title", shortenText(item.path("title").asText("입력 검증 필요"), 70));
                caution.put("message", shortenText(item.path("message").asText("호출 전 입력값 검증이 필요합니다."), 180));
                caution.put("when", "메서드 호출 전 파라미터를 구성할 때");
                putIfText(caution, "relatedClass", item.path("relatedClass").asText(""));
                putIfText(caution, "relatedMethod", item.path("relatedMethod").asText(""));
                caution.set("evidenceIds", limitEvidenceIdArray(item.path("evidenceIds"), MAX_EVIDENCE_LINKS));
                caution.put("confidence", normalizeConfidence(item.path("confidence").asDouble(0.70d)));
                String cond = item.path("condition").asText("");
                String act  = item.path("action").asText("");
                if (!cond.isBlank() || !act.isBlank()) {
                    ObjectNode sm = objectMapper.createObjectNode();
                    if (!cond.isBlank()) sm.put("condition", cond);
                    if (!act.isBlank())  sm.put("action", act);
                    caution.set("summary", sm);
                }
            }
        }

        if (cautions.isEmpty()) {
            ObjectNode caution = cautions.addObject();
            caution.put("cautionId", "CAU-001");
            caution.put("title", "필수 입력값 검증");
            caution.put("message", "필수 파라미터가 누락되면 예외가 발생할 수 있으므로 호출 전 검증하세요.");
            caution.put("when", "API 호출 전");
            caution.putArray("evidenceIds");
            caution.put("confidence", 0.60d);
        }
        return out;
    }

    private JsonNode fallbackScenarioSpecs(JsonNode structure, JsonNode refinedRules) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("overview", normalizeOverview(NullNode.getInstance(), structure));
        out.set("methodFlow", normalizeMethodFlow(NullNode.getInstance(), structure.path("methodFlowSeed")));
        ArrayNode scenarios = out.putArray("scenarios");

        ObjectNode scenario = scenarios.addObject();
        scenario.put("scenarioId", "SCN-001");
        scenario.put("title", "기본 사용 흐름");
        String seedPurpose = structure.path("overviewSeed").path("purpose").asText("");
        scenario.put("intent", seedPurpose.isBlank()
                ? "핵심 API를 순서대로 호출해 기본 기능을 구현한다."
                : shortenText(seedPurpose, 120));
        ArrayNode steps = scenario.putArray("steps");

        ArrayNode flow = out.path("methodFlow").isArray() ? (ArrayNode) out.path("methodFlow") : objectMapper.createArrayNode();
        for (int i = 0; i < flow.size() && i < MAX_STEPS_PER_SCENARIO; i++) {
            JsonNode flowStep = flow.get(i);
            ObjectNode step = steps.addObject();
            step.put("stepNo", i + 1);
            step.put("description", shortenText(flowStep.path("description").asText("핵심 메서드를 순서대로 호출한다."), 160));
            putIfText(step, "classFqn", flowStep.path("classFqn").asText(""));
            putIfText(step, "methodFqn", flowStep.path("methodFqn").asText(""));
            step.set("evidenceLinks", evidenceLinksFromSeed(flowStep));
        }

        if (steps.isEmpty()) {
            ObjectNode step = steps.addObject();
            step.put("stepNo", 1);
            step.put("description", "핵심 API를 선택하고 입력값을 구성한다.");
            step.putArray("evidenceLinks");
        }
        out.put("fallbackApplied", true);
        return out;
    }

    private JsonNode toRulesFromCautions(JsonNode cautions) {
        ArrayNode rules = objectMapper.createArrayNode();
        if (!cautions.isArray()) {
            return rules;
        }
        for (int i = 0; i < cautions.size(); i++) {
            JsonNode caution = cautions.get(i);
            ObjectNode rule = rules.addObject();
            rule.put("ruleId", String.format("RULE-%03d", i + 1));
            rule.put("name", caution.path("title").asText("주의사항"));
            rule.put("classification", "defensive");
            rule.put("description", caution.path("message").asText(""));
            JsonNode mergedFrom = caution.path("mergedFromGroups");
            rule.set("mergedFromGroups", mergedFrom.isArray() ? mergedFrom.deepCopy() : objectMapper.createArrayNode());
            rule.set("evidenceIds", limitEvidenceIdArray(caution.path("evidenceIds"), MAX_EVIDENCE_LINKS));
            rule.put("confidence", normalizeConfidence(caution.path("confidence").asDouble(0.70d)));
        }
        return rules;
    }

    private ObjectNode normalizeOverview(JsonNode rawOverview, JsonNode structure) {
        JsonNode seed = structure.path("overviewSeed");
        ObjectNode overview = objectMapper.createObjectNode();

        String repoName = firstNonBlank(seed.path("repoName").asText(""), "오픈소스 프로젝트");
        overview.put("project", firstNonBlank(rawOverview.path("project").asText(""), repoName));
        overview.put("purpose", firstNonBlank(
                rawOverview.path("purpose").asText(""),
                "공개 API를 빠르게 이해하고 실제 사용 순서를 안내한다."
        ));
        overview.put("fitSituation", firstNonBlank(
                rawOverview.path("fitSituation").asText(""),
                "처음 라이브러리를 도입하거나 기존 코드의 사용 흐름을 점검할 때 적합하다."
        ));
        overview.put("coreFeatures", firstNonBlank(
                rawOverview.path("coreFeatures").asText(""),
                "핵심 클래스/메서드, 사용 순서, 주의사항, 확장 포인트를 근거와 함께 제공한다."
        ));
        overview.put("startGuide", firstNonBlank(
                rawOverview.path("startGuide").asText(""),
                "대표 시나리오의 1단계부터 순서대로 실행하고, 각 단계의 메서드/근거 위치를 함께 확인한다."
        ));
        return overview;
    }

    private JsonNode normalizeMethodFlow(JsonNode rawFlow, JsonNode seedFlow) {
        ArrayNode source = objectMapper.createArrayNode();
        if (rawFlow != null && rawFlow.isArray() && !rawFlow.isEmpty()) {
            source.addAll((ArrayNode) rawFlow);
        } else if (seedFlow != null && seedFlow.isArray()) {
            source.addAll((ArrayNode) seedFlow);
        }

        ArrayNode out = objectMapper.createArrayNode();
        for (int i = 0; i < source.size() && out.size() < MAX_METHOD_FLOW; i++) {
            JsonNode raw = source.get(i);
            ObjectNode node = out.addObject();
            node.put("order", raw.path("order").asInt(i + 1));
            node.put("title", shortenText(raw.path("title").asText("단계"), 50));
            node.put("description", shortenText(raw.path("description").asText("핵심 메서드를 호출한다."), 120));
            String methodFqn = firstNonBlank(raw.path("methodFqn").asText(""), raw.path("fqn").asText(""));
            String classFqn = firstNonBlank(raw.path("classFqn").asText(""), ownerFromMethodFqn(methodFqn));
            putIfText(node, "classFqn", classFqn);
            putIfText(node, "methodFqn", methodFqn);
            String filePath = raw.path("filePath").asText("");
            if (isUserFacingSourcePath(filePath)) {
                putIfText(node, "filePath", filePath);
            }
            if (raw.path("startLine").canConvertToInt()) {
                node.put("startLine", raw.path("startLine").asInt());
            }
            if (raw.path("endLine").canConvertToInt()) {
                node.put("endLine", raw.path("endLine").asInt());
            }
        }
        return out;
    }

    private ArrayNode buildCoreClassDocs(JsonNode classSeed, JsonNode methodSeed) {
        Map<String, List<JsonNode>> methodsByClassId = new HashMap<>();
        Map<String, List<JsonNode>> methodsByClassFqn = new HashMap<>();
        if (methodSeed.isArray()) {
            for (JsonNode method : methodSeed) {
                String classId = method.path("classSymbolId").asText("");
                if (!classId.isBlank()) {
                    methodsByClassId.computeIfAbsent(classId, k -> new ArrayList<>()).add(method);
                }
                String classFqn = method.path("classFqn").asText("");
                if (!classFqn.isBlank()) {
                    methodsByClassFqn.computeIfAbsent(classFqn, k -> new ArrayList<>()).add(method);
                }
            }
        }

        ArrayNode out = objectMapper.createArrayNode();
        if (!classSeed.isArray()) {
            return out;
        }

        for (int i = 0; i < classSeed.size() && out.size() < MAX_CORE_CLASSES; i++) {
            JsonNode seed = classSeed.get(i);
            String classId = seed.path("symbolId").asText("");
            String classFqn = seed.path("fqn").asText("");
            ObjectNode item = out.addObject();
            item.put("classId", classId);
            item.put("packageName", seed.path("packageName").asText(""));
            item.put("className", seed.path("className").asText(""));
            item.put("fqn", classFqn);
            item.put("role", seed.path("role").asText(""));
            item.put("responsibility", seed.path("role").asText(""));
            item.put("useCase", seed.path("usage").asText(""));
            item.put("importance", seed.path("importance").asInt(0));
            putIfText(item, "filePath", seed.path("filePath").asText(""));
            if (seed.path("startLine").canConvertToInt()) {
                item.put("startLine", seed.path("startLine").asInt());
            }
            if (seed.path("endLine").canConvertToInt()) {
                item.put("endLine", seed.path("endLine").asInt());
            }

            ArrayNode keyMethods = item.putArray("keyMethods");
            // classSymbolId 기준 조회 우선, 없으면 classFqn 기반 fallback
            List<JsonNode> classMethods = new ArrayList<>(methodsByClassId.getOrDefault(classId, List.of()));
            if (classMethods.isEmpty()) {
                classMethods = new ArrayList<>(methodsByClassFqn.getOrDefault(classFqn, List.of()));
            }
            classMethods.sort(Comparator.comparingInt((JsonNode m) -> m.path("importance").asInt(0)).reversed());
            for (int m = 0; m < classMethods.size() && m < 5; m++) {
                JsonNode method = classMethods.get(m);
                keyMethods.add(method.path("fqn").asText(""));
            }
            item.putArray("relatedScenarios");
        }
        return out;
    }

    /**
     * 시나리오 스텝의 classFqn/methodFqn을 교차 참조하여 각 핵심 클래스의 relatedScenarios를 채운다.
     * 직접 참조가 없는 클래스는 모든 시나리오를 fallback으로 포함한다.
     */
    private void fillCoreClassRelatedScenarios(ArrayNode coreClasses, JsonNode scenarioSpecs) {
        JsonNode scenarios = scenarioSpecs.path("scenarios");
        if (!scenarios.isArray() || scenarios.isEmpty()) {
            return;
        }

        List<String> allScenarioIds = new ArrayList<>();
        Map<String, Set<String>> scenarioIdsByClassFqn = new LinkedHashMap<>();
        for (JsonNode scenario : scenarios) {
            String scenarioId = scenario.path("scenarioId").asText("");
            if (scenarioId.isBlank()) {
                continue;
            }
            allScenarioIds.add(scenarioId);
            JsonNode steps = scenario.path("steps");
            if (!steps.isArray()) {
                continue;
            }
            for (JsonNode step : steps) {
                String classFqn = step.path("classFqn").asText("");
                if (!classFqn.isBlank()) {
                    scenarioIdsByClassFqn.computeIfAbsent(classFqn, k -> new LinkedHashSet<>()).add(scenarioId);
                }
                String methodFqn = step.path("methodFqn").asText("");
                if (!methodFqn.isBlank()) {
                    String ownerFqn = ownerFromMethodFqn(methodFqn);
                    if (!ownerFqn.isBlank()) {
                        scenarioIdsByClassFqn.computeIfAbsent(ownerFqn, k -> new LinkedHashSet<>()).add(scenarioId);
                    }
                }
            }
        }

        for (JsonNode classNode : coreClasses) {
            if (!classNode.isObject()) {
                continue;
            }
            ObjectNode classObj = (ObjectNode) classNode;
            String fqn = classObj.path("fqn").asText("");
            Set<String> related = scenarioIdsByClassFqn.getOrDefault(fqn, Set.of());
            List<String> ids = related.isEmpty() ? allScenarioIds : new ArrayList<>(related);
            ArrayNode relatedScenarios = classObj.putArray("relatedScenarios");
            for (int i = 0; i < ids.size() && i < 3; i++) {
                relatedScenarios.add(ids.get(i));
            }
        }
    }

    private ArrayNode buildExtensionPointDocs(JsonNode extensionSeed) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!extensionSeed.isArray()) {
            return out;
        }
        for (int i = 0; i < extensionSeed.size() && i < MAX_CORE_CLASSES; i++) {
            JsonNode seed = extensionSeed.get(i);
            ObjectNode point = out.addObject();
            point.put("fqn", seed.path("fqn").asText(""));
            point.put("className", seed.path("className").asText(""));
            point.put("reason", seed.path("reason").asText(""));
            point.put("confidenceSource", seed.path("confidenceSource").asText("구조"));
            point.put("confidenceLevel", "추론".equals(seed.path("confidenceSource").asText("")) ? 0.65d : 0.85d);
            putIfText(point, "filePath", seed.path("filePath").asText(""));
        }
        return out;
    }

    private ArrayNode buildSubsystemDocs(JsonNode coreClasses, JsonNode scenarioSpecs, JsonNode refinedRules) {
        Map<String, ObjectNode> byPackage = new LinkedHashMap<>();
        if (coreClasses.isArray()) {
            for (JsonNode type : coreClasses) {
                String packageName = type.path("packageName").asText("");
                String key = topPackageKey(packageName);
                byPackage.computeIfAbsent(key, k -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("subsystemId", "ss_" + (byPackage.size() + 1));
                    node.put("label", key.isBlank() ? "core" : key);
                    node.put("description", "관련 클래스와 메서드를 묶어 사용 흐름을 설명하는 서브시스템");
                    node.put("layer", "application");
                    node.putArray("topSymbols");
                    node.putArray("relatedScenarios");
                    node.putArray("ruleIds");
                    return node;
                });
                byPackage.get(key).withArray("topSymbols").add("type:" + type.path("fqn").asText(""));
            }
        }

        ArrayNode scenarios = scenarioSpecs.path("scenarios").isArray()
                ? (ArrayNode) scenarioSpecs.path("scenarios")
                : objectMapper.createArrayNode();
        List<String> scenarioIds = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            scenarioIds.add(scenarios.get(i).path("scenarioId").asText(""));
        }

        ArrayNode rules = refinedRules.path("rules").isArray()
                ? (ArrayNode) refinedRules.path("rules")
                : objectMapper.createArrayNode();
        List<String> ruleIds = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            ruleIds.add(rules.get(i).path("ruleId").asText(""));
        }

        ArrayNode out = objectMapper.createArrayNode();
        int index = 0;
        for (ObjectNode subsystem : byPackage.values()) {
            if (index++ >= MAX_CORE_CLASSES) {
                break;
            }
            ArrayNode relatedScenarios = subsystem.withArray("relatedScenarios");
            for (int i = 0; i < scenarioIds.size() && i < 3; i++) {
                relatedScenarios.add(scenarioIds.get(i));
            }
            ArrayNode relatedRules = subsystem.withArray("ruleIds");
            for (int i = 0; i < ruleIds.size() && i < 4; i++) {
                relatedRules.add(ruleIds.get(i));
            }
            out.add(subsystem);
        }
        return out;
    }

    private String topPackageKey(String packageName) {
        String value = safeText(packageName);
        if (value.isBlank()) {
            return "core";
        }
        String[] tokens = value.split("\\.");
        if (tokens.length <= 2) {
            return value;
        }
        return tokens[0] + "." + tokens[1] + "." + tokens[2];
    }

    private ArrayNode buildCoreMethodCards(JsonNode methodSeed, JsonNode flowSeed, JsonNode cautions) {
        Map<String, List<String>> cautionByMethod = indexCautionsByMethod(cautions);
        Map<String, Integer> orderByMethod = indexMethodOrder(flowSeed);

        ArrayNode out = objectMapper.createArrayNode();
        if (!methodSeed.isArray()) {
            return out;
        }

        for (int i = 0; i < methodSeed.size() && out.size() < MAX_CORE_METHODS; i++) {
            JsonNode seed = methodSeed.get(i);
            ObjectNode card = out.addObject();

            String fqn = seed.path("fqn").asText("");
            String methodName = seed.path("methodName").asText("");
            String classFqn = seed.path("classFqn").asText("");
            String whatItDoesFull = normalizeSentence(seed.path("summarySeed").asText("핵심 동작을 수행한다."));
            String whatItDoesPreview = shortenForPreview(whatItDoesFull, MAX_METHOD_DESCRIPTION_PREVIEW);
            boolean whatItDoesTruncated = !whatItDoesPreview.equals(whatItDoesFull);
            String summaryRaw = normalizeRawSummary(seed.path("summarySeed").asText(""));
            String summaryNarrative = toNarrativeSummary(summaryRaw, classFqn, methodName);
            String summaryPreview = shortenForPreview(summaryNarrative, MAX_METHOD_DESCRIPTION_PREVIEW);
            boolean summaryTruncated = !summaryPreview.equals(summaryNarrative);
            whatItDoesFull = summaryNarrative;
            whatItDoesPreview = summaryPreview;
            whatItDoesTruncated = summaryTruncated;

            card.put("methodName", methodName);
            card.put("classFqn", classFqn);
            card.put("fqn", fqn);
            card.put("summaryRaw", summaryRaw);
            card.put("summaryNarrative", summaryNarrative);
            card.put("summaryPreview", summaryPreview);
            card.put("summaryTruncated", summaryTruncated);
            card.put("whatItDoes", summaryNarrative);
            card.put("whatItDoesPreview", whatItDoesPreview);
            card.put("whatItDoesFull", whatItDoesFull);
            card.put("whatItDoesTruncated", whatItDoesTruncated);
            card.put("whenToUse", inferWhenToUse(methodName));
            card.put("inputs", extractInputs(seed.path("signatureHint").asText("")));
            card.put("returns", extractReturns(seed.path("signatureHint").asText("")));
            card.put("changesState", inferStateChange(methodName));
            card.set("pairedWith", inferPairedMethods(methodName, methodSeed));
            card.put("callOrderNotes", formatCallOrderNote(orderByMethod.get(fqn)));
            card.set("cautions", toTextArray(cautionByMethod.getOrDefault(fqn, List.of())));
            card.put("importance", seed.path("importance").asInt(0));

            ObjectNode evidence = card.putObject("evidence");
            putIfText(evidence, "filePath", seed.path("filePath").asText(""));
            if (seed.path("startLine").canConvertToInt()) {
                evidence.put("startLine", seed.path("startLine").asInt());
            }
            if (seed.path("endLine").canConvertToInt()) {
                evidence.put("endLine", seed.path("endLine").asInt());
            }
        }
        return out;
    }

    private Map<String, List<String>> indexCautionsByMethod(JsonNode cautions) {
        Map<String, List<String>> out = new HashMap<>();
        if (!cautions.isArray()) {
            return out;
        }
        for (JsonNode caution : cautions) {
            String method = caution.path("relatedMethod").asText("");
            if (method.isBlank()) {
                continue;
            }
            out.computeIfAbsent(method, k -> new ArrayList<>())
                    .add(caution.path("message").asText(""));
        }
        return out;
    }

    private Map<String, Integer> indexMethodOrder(JsonNode flowSeed) {
        Map<String, Integer> out = new HashMap<>();
        if (!flowSeed.isArray()) {
            return out;
        }
        for (JsonNode step : flowSeed) {
            String method = step.path("methodFqn").asText("");
            if (method.isBlank()) {
                continue;
            }
            out.put(method, step.path("order").asInt(0));
        }
        return out;
    }

    private String inferWhenToUse(String methodName) {
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "옵션 정의가 끝난 뒤 실제 입력(args)을 해석할 때";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "애플리케이션 시작 시 옵션 스키마를 정의할 때";
        }
        if (lower.contains("get") || lower.contains("has")) {
            return "파싱 완료 후 값을 읽거나 분기 처리할 때";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "오류 처리 또는 사용법 안내를 출력할 때";
        }
        return "핵심 흐름 중 해당 기능이 필요할 때";
    }

    private String extractInputs(String signatureHint) {
        if (signatureHint == null || signatureHint.isBlank()) {
            return "입력 파라미터는 소속 클래스의 시그니처를 따른다.";
        }
        int idx = signatureHint.indexOf("->");
        if (idx < 0) {
            return shortenText(signatureHint, 120);
        }
        return shortenText(signatureHint.substring(0, idx).trim(), 120);
    }

    private String extractReturns(String signatureHint) {
        if (signatureHint == null || signatureHint.isBlank()) {
            return "반환 타입은 소속 메서드 시그니처를 따른다.";
        }
        int idx = signatureHint.indexOf("->");
        if (idx < 0) {
            return "반환 타입 정보가 시드에 명확하지 않음(추정)";
        }
        return shortenText(signatureHint.substring(idx + 2).trim(), 80);
    }

    private boolean inferStateChange(String methodName) {
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        return lower.startsWith("set")
                || lower.startsWith("add")
                || lower.contains("build")
                || lower.contains("parse");
    }

    private ArrayNode inferPairedMethods(String methodName, JsonNode methodSeed) {
        ArrayNode out = objectMapper.createArrayNode();
        String lower = safeText(methodName).toLowerCase(Locale.ROOT);
        List<String> keywords = new ArrayList<>();
        if (lower.contains("parse")) {
            keywords = List.of("add", "required", "get", "has");
        } else if (lower.contains("add") || lower.contains("required")) {
            keywords = List.of("parse");
        } else if (lower.contains("get") || lower.contains("has")) {
            keywords = List.of("parse");
        } else if (lower.contains("help") || lower.contains("print")) {
            keywords = List.of("parse");
        }

        if (keywords.isEmpty() || !methodSeed.isArray()) {
            return out;
        }
        for (JsonNode seed : methodSeed) {
            String peer = seed.path("fqn").asText("");
            String peerName = seed.path("methodName").asText("").toLowerCase(Locale.ROOT);
            if (peer.isBlank() || peerName.equals(lower)) {
                continue;
            }
            for (String keyword : keywords) {
                if (peerName.contains(keyword)) {
                    out.add(peer);
                    break;
                }
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private String formatCallOrderNote(Integer order) {
        if (order == null || order <= 0) {
            return "호출 순서는 시나리오 문맥에 따라 달라질 수 있음";
        }
        return "권장 호출 순서 단계: " + order;
    }

    private ArrayNode buildMethodUsageOrder(JsonNode flowSeed, JsonNode scenarios) {
        ArrayNode out = objectMapper.createArrayNode();
        if (flowSeed.isArray()) {
            for (int i = 0; i < flowSeed.size() && out.size() < MAX_METHOD_FLOW; i++) {
                JsonNode seed = flowSeed.get(i);
                ObjectNode item = out.addObject();
                item.put("order", seed.path("order").asInt(i + 1));
                item.put("title", seed.path("title").asText("단계"));
                item.put("description", seed.path("description").asText("메서드를 호출한다."));
                putIfText(item, "methodFqn", seed.path("methodFqn").asText(""));
                putIfText(item, "classFqn", seed.path("classFqn").asText(""));
            }
            return out;
        }

        // flow seed가 없으면 시나리오에서 추출
        if (scenarios != null && scenarios.isArray() && !scenarios.isEmpty()) {
            JsonNode firstScenario = scenarios.get(0).path("steps");
            if (firstScenario.isArray()) {
                for (int i = 0; i < firstScenario.size() && out.size() < MAX_METHOD_FLOW; i++) {
                    JsonNode step = firstScenario.get(i);
                    ObjectNode item = out.addObject();
                    item.put("order", i + 1);
                    item.put("title", "시나리오 단계");
                    item.put("description", step.path("description").asText("메서드를 호출한다."));
                    putIfText(item, "methodFqn", step.path("methodFqn").asText(""));
                    putIfText(item, "classFqn", step.path("classFqn").asText(""));
                }
            }
        }
        return out;
    }

    private ArrayNode buildApiEntriesCompat(JsonNode coreMethods) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!coreMethods.isArray()) {
            return out;
        }
        for (int i = 0; i < coreMethods.size() && out.size() < MAX_API_ENTRY_OUTPUT; i++) {
            JsonNode method = coreMethods.get(i);
            ObjectNode entry = out.addObject();
            entry.put("fqn", method.path("fqn").asText(""));
            String classFqn = method.path("classFqn").asText("");
            String methodName = method.path("methodName").asText("");
            String summaryFull = normalizeSentence(firstNonBlank(
                    method.path("whatItDoesFull").asText(""),
                    method.path("whatItDoes").asText("핵심 동작 수행")
            ));
            String summaryRaw = normalizeRawSummary(firstNonBlank(
                    method.path("summaryRaw").asText(""),
                    method.path("whatItDoesFull").asText(""),
                    method.path("whatItDoes").asText("핵심 동작 수행")
            ));
            String summaryNarrative = toNarrativeSummary(firstNonBlank(
                    method.path("summaryNarrative").asText(""),
                    summaryFull,
                    summaryRaw
            ), classFqn, methodName);
            String summaryPreview = shortenForPreview(summaryNarrative, MAX_METHOD_DESCRIPTION_PREVIEW);
            boolean summaryTruncated = !summaryPreview.equals(summaryNarrative);
            entry.put("summary", summaryNarrative);
            entry.put("summaryRaw", summaryRaw);
            entry.put("summaryNarrative", summaryNarrative);
            entry.put("summaryPreview", summaryPreview);
            entry.put("summaryFull", summaryNarrative);
            entry.put("summaryTruncated", summaryTruncated);
            entry.put("subsystem", shortenText(method.path("classFqn").asText("core"), 80));
            ArrayNode relatedScenarios = entry.putArray("relatedScenarios");
            relatedScenarios.add("SCN-001");
        }
        return out;
    }

    private ArrayNode normalizeEvidenceLinks(JsonNode links, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        if (links == null || !links.isArray()) {
            return out;
        }
        for (int i = 0; i < links.size() && out.size() < maxCount; i++) {
            JsonNode link = links.get(i);
            if (!link.isObject()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            if (link.path("evidenceId").canConvertToLong()) {
                item.put("evidenceId", link.path("evidenceId").asLong());
            }
            String filePath = safeText(link.path("filePath").asText(""));
            if (!filePath.isBlank() && !isUserFacingSourcePath(filePath)) {
                continue;
            }
            putIfText(item, "filePath", filePath);
            putIfText(item, "lines", firstNonBlank(
                    link.path("lines").asText(""),
                    formatLines(link.path("startLine"), link.path("endLine"))
            ));
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private ArrayNode evidenceLinksFromSeed(JsonNode seed) {
        ArrayNode out = objectMapper.createArrayNode();
        if (seed == null || seed.isMissingNode() || seed.isNull()) {
            return out;
        }
        String filePath = safeText(seed.path("filePath").asText(""));
        if (!isUserFacingSourcePath(filePath)) {
            return out;
        }
        ObjectNode link = out.addObject();
        putIfText(link, "filePath", filePath);
        putIfText(link, "lines", firstNonBlank(
                seed.path("lines").asText(""),
                formatLines(seed.path("startLine"), seed.path("endLine"))
        ));
        return out;
    }

    private Map<String, JsonNode> indexMethodSeedByFqn(JsonNode methodSeed) {
        Map<String, JsonNode> out = new HashMap<>();
        if (methodSeed == null || !methodSeed.isArray()) {
            return out;
        }
        for (JsonNode seed : methodSeed) {
            String fqn = safeText(seed.path("fqn").asText(""));
            if (!fqn.isBlank()) {
                out.putIfAbsent(fqn, seed);
            }
        }
        return out;
    }

    private String ownerFromMethodFqn(String methodFqn) {
        String value = safeText(methodFqn);
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("#")) {
            return value.substring(0, value.indexOf('#'));
        }
        int idx = value.lastIndexOf('.');
        if (idx <= 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    private boolean isUserFacingSourcePath(String filePath) {
        String normalized = normalizePath(filePath);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains(TEST_MARKER) || normalized.contains(TARGET_MARKER)) {
            return false;
        }
        // leading slash 없이 시작하는 상대 경로(e.g. "src/main/java/...")도 인식
        String withSlash = normalized.startsWith("/") ? normalized : "/" + normalized;
        return withSlash.contains(MAIN_JAVA_MARKER) || withSlash.contains(MAIN_KOTLIN_MARKER);
    }

    private String normalizePath(String path) {
        return safeText(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String formatLines(JsonNode startLine, JsonNode endLine) {
        if (startLine == null || startLine.isMissingNode() || startLine.isNull()) {
            return "";
        }
        if (endLine != null && endLine.canConvertToInt() && startLine.canConvertToInt()) {
            int start = startLine.asInt();
            int end = endLine.asInt();
            return start == end ? String.valueOf(start) : start + "-" + end;
        }
        return startLine.asText("");
    }

    private String buildCautionContext(JsonNode structure, List<LlmRequest.EvidenceSnippet> evidence) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("cautionSeed", structure.path("cautionSeed"));
        context.set("coreMethodSeed", takeFirst(structure.path("coreMethodSeed"), 12));
        context.set("qualityGate", structure.path("qualityGate"));
        context.set("evidence", toEvidenceNode(evidence, 12));
        return toJson(context);
    }

    private String buildScenarioContext(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("overviewSeed", structure.path("overviewSeed"));
        context.set("coreClassSeed", takeFirst(structure.path("coreClassSeed"), 8));
        context.set("coreMethodSeed", takeFirst(structure.path("coreMethodSeed"), 14));
        context.set("methodFlowSeed", takeFirst(structure.path("methodFlowSeed"), 6));
        context.set("cautions", takeFirst(refinedRules.path("cautions"), 8));
        context.set("evidence", toEvidenceNode(evidence, 12));
        return toJson(context);
    }

    private ArrayNode takeFirst(JsonNode arrayNode, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        if (arrayNode == null || !arrayNode.isArray()) {
            return out;
        }
        for (int i = 0; i < arrayNode.size() && i < limit; i++) {
            out.add(arrayNode.get(i));
        }
        return out;
    }

    private JsonNode toEvidenceNode(List<LlmRequest.EvidenceSnippet> evidence, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        if (evidence == null || evidence.isEmpty()) {
            return out;
        }
        for (LlmRequest.EvidenceSnippet item : evidence) {
            if (out.size() >= maxCount || item == null) {
                break;
            }
            ObjectNode node = out.addObject();
            if (item.getEvidenceId() != null) {
                node.put("evidenceId", item.getEvidenceId());
            }
            putIfText(node, "filePath", shortenText(item.getFilePath(), 220));
            if (item.getStartLine() != null) {
                if (item.getEndLine() != null) {
                    node.put("lines", item.getStartLine() + "-" + item.getEndLine());
                } else {
                    node.put("lines", String.valueOf(item.getStartLine()));
                }
            }
            putIfText(node, "snippet", shortenText(item.getSnippet(), 120));
            putIfText(node, "evidenceType", shortenText(item.getEvidenceType(), 40));
        }
        return out;
    }

    private ArrayNode limitEvidenceIdArray(JsonNode source, int maxCount) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (source == null || !source.isArray()) {
            return out;
        }
        for (int i = 0; i < source.size() && out.size() < maxCount; i++) {
            JsonNode value = source.get(i);
            String id = value.isTextual() ? value.asText("").trim()
                    : (value.canConvertToLong() ? String.valueOf(value.asLong()) : "");
            if (!id.isBlank() && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private ArrayNode toTextArray(List<String> values) {
        ArrayNode out = objectMapper.createArrayNode();
        for (String value : values) {
            String normalized = shortenText(value, 120);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private JsonNode extractArrayByKey(JsonNode raw, String key) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return NullNode.getInstance();
        }
        if (raw.isArray()) {
            return raw;
        }
        JsonNode value = raw.path(key);
        return value.isArray() ? value : NullNode.getInstance();
    }

    private boolean isResponseParseFailed(LlmException e) {
        return e != null && LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode());
    }

    private double normalizeConfidence(double value) {
        if (Double.isNaN(value) || value <= 0.0d) {
            return 0.70d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String applyLanguagePolicy(String basePrompt) {
        return basePrompt + "\n\n언어 정책:\n" + KOREAN_POLICY;
    }

    private String resolvePrimaryModel() {
        String haikuModel = llmConfig.getHaikuModel();
        if (haikuModel == null || haikuModel.isBlank()) {
            return llmConfig.getModel();
        }
        return haikuModel.trim();
    }

    private boolean canFallbackToSonnet(LlmException e, String primaryModel, String fallbackModel) {
        if (e == null) {
            return false;
        }
        boolean retryable = LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode())
                || LlmErrorCode.CLAUDE_API_CALL_FAILED.equals(e.getCode())
                || LlmErrorCode.CLAUDE_API_ERROR.equals(e.getCode());
        if (!retryable || fallbackModel == null || fallbackModel.isBlank()) {
            return false;
        }
        return primaryModel == null || !fallbackModel.equalsIgnoreCase(primaryModel.trim());
    }

    private JsonNode callClaudeWithHaikuFallback(
            String stepName,
            String systemPrompt,
            String userMessage,
            int maxTokens
    ) {
        String primaryModel = resolvePrimaryModel();
        String fallbackModel = llmConfig.getModel();
        int effectiveMaxTokens = Math.max(1, Math.min(maxTokens, llmConfig.getMaxTokens()));
        // 실제 호출에 적용된 토큰 상한을 남겨 런타임 설정 불일치를 빠르게 확인한다.
        log.info(
                "[LlmService] {} token config. requestedMaxTokens={}, globalMaxTokens={}, effectiveMaxTokens={}, primaryModel={}",
                stepName,
                maxTokens,
                llmConfig.getMaxTokens(),
                effectiveMaxTokens,
                primaryModel
        );
        try {
            return callClaudeWithModel(systemPrompt, userMessage, maxTokens, primaryModel);
        } catch (LlmException firstFailure) {
            if (!canFallbackToSonnet(firstFailure, primaryModel, fallbackModel)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmService] {} primary model failed. primaryModel={}, fallbackModel={}",
                    stepName,
                    primaryModel,
                    fallbackModel
            );
            return callClaudeWithModel(systemPrompt, userMessage, maxTokens, fallbackModel);
        }
    }

    private JsonNode callClaudeWithModel(
            String systemPrompt,
            String userMessage,
            int maxTokens,
            String model
    ) {
        int effectiveMaxTokens = Math.max(1, Math.min(maxTokens, llmConfig.getMaxTokens()));
        String requestBody = buildRequestBody(sanitizeModel(model), systemPrompt, userMessage, effectiveMaxTokens);

        for (int attempt = 1; attempt <= MAX_CLAUDE_RETRY_ATTEMPTS + 1; attempt++) {
            try {
                String raw = claudeRestClient.post()
                        .uri("/v1/messages")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return parseResponse(raw);
            } catch (LlmException e) {
                throw e;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!isRetryableStatus(status) || attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmService] Claude API call failed. status={}, message={}", status, e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = resolveRetryDelayMillis(e, attempt);
                log.warn(
                        "[LlmService] Claude API temporary failure. status={}, attempt={}/{}, retryDelayMs={}",
                        status,
                        attempt,
                        MAX_CLAUDE_RETRY_ATTEMPTS + 1,
                        delay
                );
                sleepForRetry(delay);
            } catch (Exception e) {
                if (attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmService] Claude API call failed. message={}", e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delay = Math.min(
                        BASE_RETRY_DELAY_MILLIS * (1L << Math.min(attempt - 1, 3)),
                        MAX_RETRY_DELAY_MILLIS
                );
                sleepForRetry(delay);
            }
        }
        throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
    }

    private String sanitizeModel(String model) {
        if (model == null || model.isBlank()) {
            return llmConfig.getModel();
        }
        return model.trim();
    }

    private String buildRequestBody(String model, String systemPrompt, String userMessage, int maxTokens) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("system", systemPrompt);

            ArrayNode messages = root.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.REQUEST_SERIALIZE_FAILED);
        }
    }

    private JsonNode parseResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("error")) {
                log.error("[LlmService] Claude API error. message={}", root.path("error").path("message").asText("unknown"));
                throw new LlmException(LlmErrorCode.CLAUDE_API_ERROR);
            }

            String stopReason = root.path("stop_reason").asText("");
            String textContent = "";
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText(""))) {
                    textContent = block.path("text").asText("");
                    break;
                }
            }

            String jsonPayload = stripFence(textContent.trim());
            if (jsonPayload.isBlank()) {
                throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
            }

            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception parseFail) {
                if ("max_tokens".equalsIgnoreCase(stopReason)) {
                    log.warn("[LlmService] Claude response truncated by max_tokens.");
                }
                JsonNode recovered = tryRecoverTruncatedJson(jsonPayload);
                if (recovered != null) {
                    log.warn("[LlmService] Recovered truncated response.");
                    return recovered;
                }
                throw parseFail;
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmService] Failed to parse Claude response. raw={}", raw);
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    private String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text.replace("```", "").trim();
        }
        String body = text.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.trim();
    }

    private JsonNode tryRecoverTruncatedJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String recovered = recoverPotentiallyTruncatedJson(payload);
        if (recovered.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(recovered);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String recoverPotentiallyTruncatedJson(String payload) {
        String input = payload.trim();
        int objectStart = input.indexOf('{');
        int arrayStart = input.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        int openObject = 0;
        int openArray = 0;

        for (int i = start; i < input.length(); i++) {
            char ch = input.charAt(i);
            sb.append(ch);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString && ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                openObject++;
            } else if (ch == '}') {
                openObject = Math.max(0, openObject - 1);
            } else if (ch == '[') {
                openArray++;
            } else if (ch == ']') {
                openArray = Math.max(0, openArray - 1);
            }
        }

        if (inString) {
            sb.append('"');
        }
        for (int i = 0; i < openArray; i++) {
            sb.append(']');
        }
        for (int i = 0; i < openObject; i++) {
            sb.append('}');
        }
        return sb.toString()
                .replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]")
                .trim();
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private long resolveRetryDelayMillis(RestClientResponseException e, int attempt) {
        String retryAfter = null;
        if (e.getResponseHeaders() != null) {
            retryAfter = e.getResponseHeaders().getFirst("retry-after");
        }
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, MAX_RETRY_DELAY_MILLIS);
                }
            } catch (NumberFormatException ignored) {
                // 헤더 값이 비정상이면 기본 백오프로 진행한다.
            }
        }
        return Math.min(
                BASE_RETRY_DELAY_MILLIS * (1L << Math.min(attempt - 1, 3)),
                MAX_RETRY_DELAY_MILLIS
        );
    }

    private void sleepForRetry(long delayMillis) {
        try {
            Thread.sleep(Math.max(delayMillis, 0L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
        }
    }

    private void putIfText(ObjectNode node, String key, String value) {
        if (node == null || key == null || value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isBlank()) {
            node.put(key, trimmed);
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String shortenText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String normalizeSentence(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "핵심 동작 수행";
        }
        return normalized;
    }

    private String normalizeRawSummary(String text) {
        String raw = safeText(text).replaceAll("\\s+", " ").trim();
        if (raw.isBlank()) {
            return "핵심 동작 수행";
        }
        return raw;
    }

    private String toNarrativeSummary(String rawSummary, String classFqn, String methodName) {
        String normalized = normalizeSentence(rawSummary);
        if (looksNarrativeSummary(normalized)) {
            return normalized;
        }
        String methodRef = buildMethodReference(classFqn, methodName);
        if (methodRef.isBlank()) {
            return normalized;
        }
        return "메서드 " + methodRef + "에서 " + normalized;
    }

    private boolean looksNarrativeSummary(String summary) {
        String value = safeText(summary);
        if (value.isBlank()) {
            return false;
        }
        return value.startsWith("메서드 ")
                || value.contains("에서 ")
                || value.endsWith("입니다.")
                || value.endsWith("합니다.")
                || value.endsWith("한다.");
    }

    private String buildMethodReference(String classFqn, String methodName) {
        String className = safeText(classFqn);
        String method = safeText(methodName);
        if (className.isBlank() && method.isBlank()) {
            return "";
        }
        if (className.isBlank()) {
            return method + "()";
        }
        if (method.isBlank()) {
            return className;
        }
        return className + "#" + method;
    }

    private String shortenForPreview(String text, int maxLength) {
        String value = normalizeSentence(text);
        if (value.length() <= maxLength) {
            return value;
        }

        int sentenceBoundary = findSentenceBoundary(value, maxLength);
        if (sentenceBoundary >= Math.max(1, maxLength / 2)) {
            return value.substring(0, sentenceBoundary).trim() + "...";
        }

        int wordBoundary = value.lastIndexOf(' ', maxLength);
        if (wordBoundary >= Math.max(1, maxLength / 2)) {
            return value.substring(0, wordBoundary).trim() + "...";
        }

        return value.substring(0, maxLength).trim() + "...";
    }

    private int findSentenceBoundary(String text, int limit) {
        int scanLimit = Math.min(limit, text.length() - 1);
        for (int i = scanLimit; i >= 0; i--) {
            char current = text.charAt(i);
            if (current == '.' || current == '!' || current == '?') {
                return i + 1;
            }
        }
        return -1;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}
