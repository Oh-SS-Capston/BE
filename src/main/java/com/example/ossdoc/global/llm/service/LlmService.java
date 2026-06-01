package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.llm.dto.json.LlmResult;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.dto.response.LlmResponse;
import com.example.ossdoc.global.llm.entity.LlmScenarioCache;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.example.ossdoc.global.llm.model.LlmContextBundle;
import com.example.ossdoc.global.llm.service.support.LlmClaudeClientSupport;
import com.example.ossdoc.global.llm.service.support.LlmPromptCatalog;
import com.example.ossdoc.global.llm.service.support.LlmServiceBuildSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * LLM 파이프라인 서비스.
 * 서비스는 단계 오케스트레이션만 담당하고,
 * JSON 정규화/조합 상세 로직은 지원 컴포넌트로 위임한다.
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

    private static final int MAX_CAUTIONS = 12;
    private static final int MAX_SCENARIOS = 2;
    private static final int MAX_STEPS_PER_SCENARIO = 4;

    private static final int TOKENS_CAUTIONS = 4000;
    private static final int TOKENS_SCENARIOS = 10000;
    private static final int CONTEXT_LIMIT_CAUTIONS_COMPACT = 14000;
    private static final int CONTEXT_LIMIT_SCENARIOS_COMPACT = 22000;

    private final ObjectMapper objectMapper;
    private final ArtifactService artifactService;
    private final RepoRunRepository repoRunRepository;
    private final LlmInputAssemblerService llmInputAssemblerService;
    private final LlmScenarioCacheService llmScenarioCacheService;
    private final LlmClaudeClientSupport llmClaudeClientSupport;
    private final LlmServiceBuildSupport llmServiceBuildSupport;

    /**
     * LLM 정제 파이프라인 실행.
     */
    @Transactional
    public LlmResponse refine(LlmRequest request) {
        log.info("[LlmService] Refinement start. runId={}", request.getRunId());

        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new LlmException(LlmErrorCode.RUN_NOT_FOUND));

        LlmContextBundle bundle = llmInputAssemblerService.assemble(request);
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
                llmClaudeClientSupport.resolvePrimaryModel(),
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

        JsonNode fileTreeDocs = buildFileTreeDocs(structure);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_FILE_TREE_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_FILE_TREE_DOCS, fileTreeDocs
        );

        log.info("[LlmService] Refinement complete. runId={}", request.getRunId());

        LlmResult result = LlmResult.builder()
                .runId(request.getRunId())
                .refinedRules(toSerializable(refinedRules))
                .scenarioSpecs(toSerializable(scenarioSpecs))
                .subsystemSummaries(toSerializable(subsystemSummaries))
                .apiDocs(toSerializable(apiDocs))
                .fileTreeDocs(toSerializable(fileTreeDocs))
                .scenarioCacheId(scenarioCache.getCacheId())
                .build();

        return new LlmResponse(request.getRunId(), result);
    }

    /**
     * Step 1: rule 후보를 caution/rules 계약으로 정규화한다.
     */
    private JsonNode generateCautions(JsonNode structure, List<LlmRequest.EvidenceSnippet> evidence) {
        String context = llmServiceBuildSupport.buildCautionContext(structure, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP1_REFINED_RULES, context.length());

        JsonNode cautions = generateWithRetryPlan(
                STEP1_REFINED_RULES,
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_CAUTIONS, MAX_CAUTIONS)),
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_CAUTIONS_COMPACT, Math.min(8, MAX_CAUTIONS))),
                context,
                TOKENS_CAUTIONS,
                CONTEXT_LIMIT_CAUTIONS_COMPACT,
                raw -> llmServiceBuildSupport.normalizeCautions(raw, structure),
                () -> llmServiceBuildSupport.fallbackCautions(structure)
        );

        ObjectNode out = objectMapper.createObjectNode();
        JsonNode ruleDocs = llmServiceBuildSupport.toRulesFromCautions(cautions.path("cautions"));
        out.set("cautions", cautions.path("cautions"));
        out.set("rules", ruleDocs);
        out.set("qualityGate", llmServiceBuildSupport.buildRefinedRuleQualityGate(ruleDocs, cautions.path("cautions")));
        out.put("cautionCount", cautions.path("cautions").size());
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 2: 프로젝트 개요 + 대표 시나리오 + 메서드 사용 순서를 생성한다.
     * api_flow toggle ON이면 진입점 호출 경로 요약을 컨텍스트에 추가한다.
     */
    private JsonNode generateScenarioSpecs(
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        String context = llmServiceBuildSupport.buildScenarioContext(structure, refinedRules, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP2_SCENARIO_SPECS, context.length());

        return generateWithRetryPlan(
                STEP2_SCENARIO_SPECS,
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_SCENARIOS, MAX_SCENARIOS, MAX_STEPS_PER_SCENARIO)),
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_SCENARIOS_COMPACT, Math.min(3, MAX_SCENARIOS))),
                context,
                TOKENS_SCENARIOS,
                CONTEXT_LIMIT_SCENARIOS_COMPACT,
                raw -> llmServiceBuildSupport.normalizeScenarioSpecs(raw, structure),
                () -> llmServiceBuildSupport.fallbackScenarioSpecs(structure, refinedRules)
        );
    }

    /**
     * Step 3: 서브시스템 요약 결과를 조합한다.
     * super-cluster toggle ON이면 요약 단위를 super-cluster(모듈 수준)로 전환한다.
     */
    private JsonNode buildSubsystemSummaries(JsonNode structure, JsonNode scenarioSpecs, JsonNode refinedRules) {
        log.info("[LlmService] {} (deterministic)", STEP3_SUBSYSTEM_SUMMARIES);
        ObjectNode out = objectMapper.createObjectNode();
        out.set("coreClasses", llmServiceBuildSupport.buildCoreClassDocs(structure.path("coreClassSeed"), structure.path("coreMethodSeed")));
        llmServiceBuildSupport.fillCoreClassRelatedScenarios((com.fasterxml.jackson.databind.node.ArrayNode) out.path("coreClasses"), scenarioSpecs);
        out.set("extensionPoints", llmServiceBuildSupport.buildExtensionPointDocs(structure.path("extensionSeed")));

        JsonNode superSubsystems = structure.path("superSubsystems");
        if (superSubsystems.isArray() && !superSubsystems.isEmpty()) {
            out.set("subsystems", llmServiceBuildSupport.buildSuperClusterSubsystemDocs(
                    superSubsystems, out.path("coreClasses"), scenarioSpecs, refinedRules));
            out.put("superClusterApplied", true);
            log.info("[LlmService] step③: super-cluster 요약 단위 적용. superCount={}", superSubsystems.size());
        } else {
            out.set("subsystems", llmServiceBuildSupport.buildSubsystemDocs(out.path("coreClasses"), scenarioSpecs, refinedRules));
            out.put("superClusterApplied", false);
        }

        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 4: API 문서 결과를 조합한다.
     * api_flow toggle ON이면 각 메서드 카드에 호출 경로 참고 정보를 보강한다.
     */
    private JsonNode buildApiDocs(JsonNode structure, JsonNode scenarioSpecs, JsonNode refinedRules) {
        log.info("[LlmService] {} (deterministic)", STEP4_API_DOCS);

        ObjectNode out = objectMapper.createObjectNode();
        out.set("coreMethods", llmServiceBuildSupport.buildCoreMethodCards(
                structure.path("coreMethodSeed"),
                structure.path("methodFlowSeed"),
                refinedRules.path("cautions")
        ));

        // api_flow 보강: TYPE FQN 기준으로 coreMethods에 연결 후 apiEntries가 상속
        JsonNode apiFlowTraces = structure.path("apiFlowTraces");
        if (apiFlowTraces.isArray() && !apiFlowTraces.isEmpty()) {
            llmServiceBuildSupport.enrichCoreMethodsWithFlowTraces(
                    (com.fasterxml.jackson.databind.node.ArrayNode) out.path("coreMethods"), apiFlowTraces);
        }

        out.set("methodUsageOrder", llmServiceBuildSupport.buildMethodUsageOrder(
                structure.path("methodFlowSeed"),
                scenarioSpecs.path("scenarios")
        ));
        out.set("apiEntries", llmServiceBuildSupport.buildApiEntriesCompat(out.path("coreMethods")));
        out.set("qualityGate", llmServiceBuildSupport.buildApiDocQualityGate(out.path("coreMethods")));

        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * Step 5: 파일 트리/근거 위치 결과를 조합한다.
     * super-cluster toggle ON이면 디렉터리 항목에 모듈 라벨을 추가한다.
     */
    private JsonNode buildFileTreeDocs(JsonNode structure) {
        log.info("[LlmService] {} (deterministic)", STEP5_FILE_TREE_DOCS);

        ObjectNode out = objectMapper.createObjectNode();
        out.set("directories", structure.path("directories").deepCopy());
        out.set("evidenceLocations", structure.path("evidenceIndex").deepCopy());
        JsonNode coreMethods = llmServiceBuildSupport.buildFileLocationMethods(
                structure.path("coreMethodSeed"),
                llmServiceBuildSupport.buildClassSourceMap(structure.path("coreClassSeed"))
        );
        out.set("coreMethods", coreMethods);

        // super-cluster module-grain 라벨 주입 (보조 입력)
        JsonNode superSubsystems = structure.path("superSubsystems");
        if (superSubsystems.isArray() && !superSubsystems.isEmpty()) {
            out.set("moduleLabels", llmServiceBuildSupport.buildModuleLabels(superSubsystems));
        }

        out.set("qualityGate", llmServiceBuildSupport.buildFileTreeDocQualityGate(coreMethods));
        out.put("fallbackApplied", false);
        out.put("deterministicSeedApplied", true);
        out.put("contractVersion", "guide-v1");
        return out;
    }

    /**
     * 파싱 실패 시 compact prompt 재시도 후 fallback까지 수행하는 공통 실행기.
     */
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
            JsonNode raw = llmClaudeClientSupport.callClaudeWithHaikuFallback(stepName, normalPrompt, context, maxTokens);
            return normalizer.apply(raw);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn("[LlmService] {} parse failed. retrying compact mode.", stepName);
        }

        String compactContext = context.length() <= compactContextLimit
                ? context
                : context.substring(0, compactContextLimit);
        try {
            JsonNode raw = llmClaudeClientSupport.callClaudeWithHaikuFallback(stepName + " compact", compactPrompt, compactContext, maxTokens);
            return normalizer.apply(raw);
        } catch (LlmException secondFailure) {
            if (!isResponseParseFailed(secondFailure)) {
                throw secondFailure;
            }
            log.warn("[LlmService] {} fallback applied.", stepName);
            return fallbackSupplier.get();
        }
    }

    private Object toSerializable(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return objectMapper.convertValue(node, Object.class);
    }

    private boolean isResponseParseFailed(LlmException e) {
        return e != null && LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode());
    }

    private String applyLanguagePolicy(String basePrompt) {
        return basePrompt + "\n\n언어 정책:\n" + LlmPromptCatalog.KOREAN_POLICY;
    }
}
