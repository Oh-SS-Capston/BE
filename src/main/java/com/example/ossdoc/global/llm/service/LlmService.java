package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.llm.dto.json.LlmResult;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.enums.LlmProvider;
import com.example.ossdoc.global.llm.dto.response.LlmResponse;
import com.example.ossdoc.global.llm.entity.LlmScenarioCache;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.example.ossdoc.global.llm.config.LlmGenerationProperties;
import com.example.ossdoc.global.llm.config.LlmGenerationProperties.ScenarioCallMode;
import com.example.ossdoc.global.llm.config.LlmOutputProperties;
import com.example.ossdoc.global.llm.model.LlmContextBundle;
import com.example.ossdoc.global.llm.service.support.LlmArtifactWriter;
import com.example.ossdoc.global.llm.service.support.LlmChatClient;
import com.example.ossdoc.global.llm.service.support.LlmChatClientResolver;
import com.example.ossdoc.global.llm.service.support.LlmPromptCatalog;
import com.example.ossdoc.global.llm.service.support.LlmScenarioContextSupport;
import com.example.ossdoc.global.llm.service.support.LlmServiceBuildSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
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

    private static final int CONTEXT_LIMIT_CAUTIONS_COMPACT = 32000;
    private static final int CONTEXT_LIMIT_SCENARIOS_COMPACT = 48000;

    private final ObjectMapper objectMapper;
    private final LlmArtifactWriter llmArtifactWriter;
    private final RepoRunRepository repoRunRepository;
    private final LlmInputAssemblerService llmInputAssemblerService;
    private final LlmScenarioCacheService llmScenarioCacheService;
    private final LlmChatClientResolver llmChatClientResolver;
    private final LlmServiceBuildSupport llmServiceBuildSupport;
    private final LlmScenarioContextSupport llmScenarioContextSupport;
    private final LlmGenerationProperties llmGenerationProperties;
    private final LlmOutputProperties llmOutputProperties;

    /**
     * LLM 정제 파이프라인 실행.
     */
    @Transactional
    public LlmResponse refine(LlmRequest request) {
        log.info("[LlmService] Refinement start. runId={}", request.getRunId());

        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new LlmException(LlmErrorCode.RUN_NOT_FOUND));

        /*
         * 제공자는 run이 정한다. 요청에 없으면 설정 기본값이다.
         * 여기서 한 번 확정해 아래 단계 전부가 같은 클라이언트를 쓰게 한다 —
         * 단계마다 다시 고르면 한 run의 산출물이 두 모델에서 나올 수 있다.
         */
        LlmChatClient chatClient = llmChatClientResolver.resolve(resolveRequestedProvider(request));
        log.info("[LlmService] provider={}, model={}", chatClient.provider(), chatClient.resolvePrimaryModel());

        LlmContextBundle bundle = llmInputAssemblerService.assemble(request);
        JsonNode structure = objectMapper.valueToTree(bundle.structureEngineOutput());
        List<LlmRequest.EvidenceSnippet> evidence = bundle.evidenceBundle() == null
                ? List.of()
                : bundle.evidenceBundle();

        JsonNode refinedRules = reusableCautions(request.getRunId())
                .map(this::refreshReusedGate)
                .orElseGet(() -> generateCautions(chatClient, structure, evidence));
        llmArtifactWriter.write(
                run, ArtifactKind.LLM_REFINED_RULES, ARTIFACT_SCHEMA_VERSION, PATH_REFINED_RULES, refinedRules
        );

        JsonNode scenarioSpecs = generateScenarioSpecs(chatClient, structure, refinedRules, evidence);
        llmArtifactWriter.write(
                run, ArtifactKind.LLM_SCENARIO_SPECS, ARTIFACT_SCHEMA_VERSION, PATH_SCENARIO_SPECS, scenarioSpecs
        );
        // local-only 모드에서는 DB 저장을 전부 생략하므로 시나리오 캐시도 남기지 않는다.
        Long scenarioCacheId = null;
        if (llmOutputProperties.isLocalOnly()) {
            log.info("[LlmService] local-only 모드 — llm_scenario_cache 저장을 생략합니다.");
        } else {
            LlmScenarioCache scenarioCache = llmScenarioCacheService.upsertScenarioCache(
                    run,
                    scenarioSpecs,
                    chatClient.resolvePrimaryModel(),
                    SCENARIO_PROMPT_VERSION
            );
            scenarioCacheId = scenarioCache.getCacheId();
        }

        JsonNode subsystemSummaries = buildSubsystemSummaries(structure, scenarioSpecs, refinedRules);
        llmArtifactWriter.write(
                run, ArtifactKind.LLM_SUBSYSTEM_SUMMARIES, ARTIFACT_SCHEMA_VERSION, PATH_SUBSYSTEM_SUMMARIES, subsystemSummaries
        );

        JsonNode apiDocs = buildApiDocs(structure, scenarioSpecs, refinedRules);
        llmArtifactWriter.write(
                run, ArtifactKind.LLM_API_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_API_DOCS, apiDocs
        );

        JsonNode fileTreeDocs = buildFileTreeDocs(structure);
        llmArtifactWriter.write(
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
                .scenarioCacheId(scenarioCacheId)
                .build();

        return new LlmResponse(request.getRunId(), result);
    }

    /**
     * step ① 재사용 스위치가 켜져 있고 이전 산출물이 남아 있으면 그것을 쓴다.
     *
     * <p>step ②를 여러 번 비교하는 동안 step ①은 매번 같은 결과를 40분에 걸쳐 다시 만든다.
     * 그 시간이 실험 횟수를 제약하고 있어 건너뛸 수 있게 한다.</p>
     *
     * <p>조용히 건너뛰지 않는다. 재사용된 실행의 로그를 나중에 읽고
     * "step ①이 40분 만에 끝났다"로 오독하면 그 자체가 잘못된 실측이 된다.</p>
     */
    private Optional<JsonNode> reusableCautions(String runId) {
        if (!llmGenerationProperties.isReuseCautions()) {
            return Optional.empty();
        }
        if (!llmOutputProperties.isLocalOnly()) {
            log.warn("[LlmService] reuse-cautions는 local-only 모드에서만 동작합니다. STEP①을 새로 생성합니다.");
            return Optional.empty();
        }
        Optional<JsonNode> cached = llmArtifactWriter.readLocal(runId, PATH_REFINED_RULES);
        cached.ifPresentOrElse(
                node -> log.warn(
                        "[LlmService] {} 재사용 — 이전 산출물을 그대로 씁니다(caution {}개)."
                                + " 이 실행의 STEP① 시간/토큰은 측정값이 아닙니다.",
                        STEP1_REFINED_RULES, node.path("cautions").size()
                ),
                () -> log.info("[LlmService] reuse-cautions가 켜져 있지만 이전 산출물이 없어 새로 생성합니다.")
        );
        return cached;
    }

    /**
     * 재사용한 STEP① 산출물의 qualityGate만 현재 산식으로 다시 계산한다.
     *
     * <p>cautions/rules는 손대지 않고 LLM 호출도 없다. 게이트 계산은 결정론이므로
     * 같은 cautions에 현재 산식을 적용한 값이 곧 이 실행의 값이고, 파일에 남아 있던 값은
     * 그 산출물을 만들던 시점의 산식으로 잰 값이다.</p>
     *
     * <p>이걸 안 하면 낡은 게이트가 그대로 실려 나가 현재 로직으로 잰 값처럼 읽힌다.
     * 실제로 run A에서 {@code targetSuitabilityAvg=1.0}이 그대로 남는 바람에,
     * 기본값 1.0을 삼키던 버그를 고쳤는지 산출물만 보고는 판단할 수 없었다.</p>
     */
    private JsonNode refreshReusedGate(JsonNode reused) {
        if (!(reused instanceof ObjectNode node)) {
            return reused;
        }
        node.set("qualityGate", llmServiceBuildSupport.buildRefinedRuleQualityGate(
                node.path("rules"), node.path("cautions")
        ));
        log.info("[LlmService] 재사용 산출물의 qualityGate만 현재 산식으로 재계산했습니다(cautions 내용은 그대로).");
        return node;
    }

    /**
     * Step 1: rule 후보를 caution/rules 계약으로 정규화한다.
     */
    private JsonNode generateCautions(
            LlmChatClient chatClient,
            JsonNode structure,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        String context = llmServiceBuildSupport.buildCautionContext(structure, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP1_REFINED_RULES, context.length());

        int maxCautions = llmGenerationProperties.getMaxCautions();
        JsonNode cautions = generateWithRetryPlan(
                chatClient,
                STEP1_REFINED_RULES,
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_CAUTIONS, maxCautions)),
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_CAUTIONS_COMPACT, Math.min(8, maxCautions))),
                context,
                llmGenerationProperties.getTokensCautions(),
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
     *
     * <p>{@code ossdoc.llm.generation.scenario-call-mode}로 호출 분해 방식을 고른다.
     * 두 모드의 산출물은 같은 정규화 경로를 통과하므로 설정 한 줄로 A/B가 된다.</p>
     */
    private JsonNode generateScenarioSpecs(
            LlmChatClient chatClient,
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        JsonNode specs = generateScenarioSpecsByMode(chatClient, structure, refinedRules, evidence);

        // 게이트는 여기 한 곳에서만 붙인다. 두 모드와 fallback이 전부 이 함수를 통과하므로
        // 같은 단위로 기록되어야 A/B 비교가 성립한다.
        if (specs instanceof ObjectNode out) {
            out.set("qualityGate", llmServiceBuildSupport.buildScenarioSpecsQualityGate(
                    out.path("scenarios"), out.path("overview")));
            log.info("[LlmService] {} 서술 채움 {}/{} ({}%), 채움말 {}칸.",
                    STEP2_SCENARIO_SPECS,
                    out.path("qualityGate").path("narrativeFieldFilled").asInt(),
                    out.path("qualityGate").path("narrativeFieldTotal").asInt(),
                    String.format("%.0f", out.path("qualityGate").path("narrativeFieldCoverage").asDouble() * 100),
                    out.path("qualityGate").path("fillerFieldCount").asInt());
        }
        return specs;
    }

    private JsonNode generateScenarioSpecsByMode(
            LlmChatClient chatClient,
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        if (llmGenerationProperties.getScenarioCallMode() == ScenarioCallMode.PER_SCENARIO) {
            return generateScenarioSpecsPerScenario(chatClient, structure, refinedRules, evidence);
        }
        return generateScenarioSpecsSingle(chatClient, structure, refinedRules, evidence);
    }

    /**
     * PER_SCENARIO 모드: overview 1회 + 시나리오마다 1회.
     *
     * <p>모델이 "몇 장을 썼는지"를 관리할 필요가 없다는 것이 핵심이다. 순회는 이 for문이 하고
     * 모델은 한 장만 본다. 시나리오 하나가 실패해도 그 장만 시드 문구로 떨어지고 나머지는 남는다.</p>
     *
     * <p>골격이 비어 있으면 채울 칸이 없으므로 SINGLE 모드로 되돌린다.
     * 골격 없이 쪼개면 시나리오 수를 모델이 정하게 되어 분해의 전제가 무너진다.</p>
     */
    private JsonNode generateScenarioSpecsPerScenario(
            LlmChatClient chatClient,
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        JsonNode scenarioSeed = structure.path("scenarioSeed");
        if (!scenarioSeed.isArray() || scenarioSeed.isEmpty()) {
            log.warn("[LlmService] {} 골격이 비어 PER_SCENARIO를 쓸 수 없습니다. SINGLE로 처리합니다.",
                    STEP2_SCENARIO_SPECS);
            return generateScenarioSpecsSingle(chatClient, structure, refinedRules, evidence);
        }

        String overviewContext = llmScenarioContextSupport.buildOverviewContext(structure, refinedRules);
        log.info("[LlmService] {} overview 호출 (contextChars={})",
                STEP2_SCENARIO_SPECS, overviewContext.length());
        JsonNode overviewRaw = generateWithRetryPlan(
                chatClient,
                STEP2_SCENARIO_SPECS + " overview",
                applyLanguagePolicy(LlmPromptCatalog.PROMPT_OVERVIEW),
                applyLanguagePolicy(LlmPromptCatalog.PROMPT_OVERVIEW),
                overviewContext,
                llmGenerationProperties.getTokensOverview(),
                CONTEXT_LIMIT_SCENARIOS_COMPACT,
                raw -> raw,
                NullNode::getInstance
        );

        ObjectNode out = llmServiceBuildSupport.buildScenarioSpecsShell(
                overviewRaw.path("overview"), structure);
        ArrayNode scenarios = (ArrayNode) out.path("scenarios");

        for (int i = 0; i < scenarioSeed.size(); i++) {
            JsonNode seedScenario = scenarioSeed.get(i);
            String scenarioId = seedScenario.path("scenarioId").asText(String.format("SCN-%03d", i + 1));
            String stepLabel = STEP2_SCENARIO_SPECS + " " + scenarioId;

            String context = llmScenarioContextSupport.buildOneScenarioContext(
                    structure, refinedRules, evidence, scenarioSeed, i);
            log.info("[LlmService] {} ({}/{}, contextChars={}, seedSteps={})",
                    stepLabel, i + 1, scenarioSeed.size(), context.length(),
                    seedScenario.path("steps").size());

            String prompt = applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_SCENARIO_ONE, scenarioId));
            JsonNode scenario = generateWithRetryPlan(
                    chatClient,
                    stepLabel,
                    prompt,
                    prompt,
                    context,
                    llmGenerationProperties.tokensForScenario(seedScenario.path("steps").size()),
                    CONTEXT_LIMIT_SCENARIOS_COMPACT,
                    raw -> llmServiceBuildSupport.normalizeOneScenario(seedScenario, raw, structure),
                    () -> llmServiceBuildSupport.fallbackOneScenario(seedScenario, structure)
            );
            if (scenario != null && scenario.isObject()) {
                scenarios.add(scenario);
            }
        }

        log.info("[LlmService] {} PER_SCENARIO 완료. 호출 {}회(개요 1 + 시나리오 {}), 시나리오 {}개 생성.",
                STEP2_SCENARIO_SPECS, scenarioSeed.size() + 1, scenarioSeed.size(), scenarios.size());
        return out;
    }

    /**
     * SINGLE 모드: 한 호출로 골격 전체를 채운다. 5차까지의 방식이자 A/B 기준선이다.
     */
    private JsonNode generateScenarioSpecsSingle(
            LlmChatClient chatClient,
            JsonNode structure,
            JsonNode refinedRules,
            List<LlmRequest.EvidenceSnippet> evidence
    ) {
        String context = llmServiceBuildSupport.buildScenarioContext(structure, refinedRules, evidence);
        log.info("[LlmService] {} (contextChars={})", STEP2_SCENARIO_SPECS, context.length());

        int maxScenarios = llmGenerationProperties.getMaxScenarios();
        return generateWithRetryPlan(
                chatClient,
                STEP2_SCENARIO_SPECS,
                applyLanguagePolicy(String.format(
                        LlmPromptCatalog.PROMPT_SCENARIOS,
                        maxScenarios,
                        llmGenerationProperties.getMaxStepsPerScenario()
                )),
                applyLanguagePolicy(String.format(LlmPromptCatalog.PROMPT_SCENARIOS_COMPACT, Math.min(3, maxScenarios))),
                context,
                llmGenerationProperties.getTokensScenarios(),
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
                refinedRules.path("cautions"),
                scenarioSpecs.path("scenarios")
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
            LlmChatClient chatClient,
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
            JsonNode raw = chatClient.call(stepName, normalPrompt, context, maxTokens);
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
            JsonNode raw = chatClient.call(stepName + " compact", compactPrompt, compactContext, maxTokens);
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

    /**
     * 요청이 지정한 제공자를 파싱한다.
     *
     * <p>값이 없으면 null을 돌려 resolver가 설정 기본값을 쓰게 한다.
     * 인식할 수 없는 값은 기본값으로 흘리지 않고 실패시킨다 — 오타 하나로 의도와 다른 모델이
     * 조용히 돌면 비용과 산출물 품질이 함께 어긋나고, 캐시 키도 다른 값으로 굳는다.</p>
     */
    private LlmProvider resolveRequestedProvider(LlmRequest request) {
        String raw = request.getProvider();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        LlmProvider parsed = LlmProvider.from(raw);
        if (parsed == null) {
            log.warn("[LlmService] 인식할 수 없는 provider 요청. value={}", raw);
            throw new LlmException(LlmErrorCode.PROVIDER_NOT_AVAILABLE);
        }
        return parsed;
    }

    private boolean isResponseParseFailed(LlmException e) {
        return e != null && LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode());
    }

    private String applyLanguagePolicy(String basePrompt) {
        return basePrompt + "\n\n언어 정책:\n" + LlmPromptCatalog.KOREAN_POLICY;
    }
}
