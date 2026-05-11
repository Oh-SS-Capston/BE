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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파이프라인 산출물을 기반으로 LLM 정제 결과를 생성한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final String ARTIFACT_SCHEMA_VERSION = "1.0";
    private static final String SCENARIO_PROMPT_VERSION = "scenario-spec-v2";
    private static final int MAX_CLAUDE_RETRY_ATTEMPTS = 3;
    private static final long BASE_RETRY_DELAY_MILLIS = 1500L;
    private static final long MAX_RETRY_DELAY_MILLIS = 15000L;

    private static final int MAX_REFINED_RULES_FOR_CONTEXT = 80;
    private static final int MAX_SCENARIOS_FOR_CONTEXT = 30;
    private static final int MAX_STEPS_PER_SCENARIO_FOR_CONTEXT = 6;
    private static final int MAX_SUBSYSTEMS_FOR_CONTEXT = 18;
    private static final int MAX_API_ENTRIES_FOR_CONTEXT = 50;
    private static final int REFINED_RULE_CHUNK_SIZE = 12;
    private static final int MAX_REFINED_RULES_PER_CHUNK = 8;
    private static final int MAX_MERGED_REFINED_RULES = 40;
    private static final int MAX_MERGED_GROUPS_PER_RULE = 6;
    private static final int MAX_MERGED_EVIDENCE_IDS_PER_RULE = 10;

    // Step5는 public API 문서화 목적이므로 입력/출력 예산을 별도로 강하게 제한한다.
    private static final int MAX_FILE_TREE_RULES_FOR_CONTEXT = 16;
    private static final int MAX_FILE_TREE_SCENARIOS_FOR_CONTEXT = 10;
    private static final int MAX_FILE_TREE_STEPS_PER_SCENARIO = 3;
    private static final int MAX_FILE_TREE_API_ENTRIES_FOR_CONTEXT = 24;
    private static final int MAX_FILE_TREE_RELATED_SCENARIOS_PER_API = 2;

    // 단계별 출력 토큰 예산
    private static final int MAX_TOKENS_REFINED_RULES_CHUNK = 1500;
    private static final int MAX_TOKENS_SCENARIO_SPECS = 1800;
    private static final int MAX_TOKENS_SUBSYSTEM_SUMMARIES = 1500;
    private static final int MAX_TOKENS_API_DOCS = 1800;
    private static final int MAX_TOKENS_FILE_TREE_DOCS = 3200;

    // Step2 안정화를 위한 시나리오 출력 제한
    private static final int MAX_SCENARIOS_OUTPUT = 8;
    private static final int MAX_STEPS_PER_SCENARIO_OUTPUT = 4;
    private static final int MAX_EVIDENCE_LINKS_PER_STEP_OUTPUT = 2;
    private static final int MAX_SCENARIO_TITLE_LENGTH = 80;
    private static final int MAX_SCENARIO_SUBSYSTEM_LENGTH = 60;
    private static final int MAX_SCENARIO_STEP_DESCRIPTION_LENGTH = 140;
    private static final int MAX_SCENARIO_RETRY_CONTEXT_CHARS = 12000;
    private static final int MAX_SUBSYSTEMS_OUTPUT = 12;
    private static final int MAX_TOP_SYMBOLS_PER_SUBSYSTEM_OUTPUT = 8;
    private static final int MAX_RULE_IDS_PER_SUBSYSTEM_OUTPUT = 10;
    private static final int MAX_SUBSYSTEM_LABEL_LENGTH = 60;
    private static final int MAX_SUBSYSTEM_DESCRIPTION_LENGTH = 140;
    private static final int MAX_SUBSYSTEM_RETRY_CONTEXT_CHARS = 14000;
    private static final int MAX_API_ENTRIES_OUTPUT = 12;
    private static final int MAX_API_RELATED_SCENARIOS_PER_ENTRY_OUTPUT = 2;
    private static final int MAX_API_TEXT_LENGTH = 120;
    private static final int MAX_API_RETRY_CONTEXT_CHARS = 10000;
    private static final int MAX_API_MINI_RETRY_CONTEXT_CHARS = 7000;
    private static final int MAX_FILE_TREE_DIRS_OUTPUT = 14;
    private static final int MAX_FILES_PER_DIR_OUTPUT = 10;
    private static final int MAX_CLASSES_PER_FILE_OUTPUT = 6;
    private static final int MAX_METHODS_PER_CLASS_OUTPUT = 8;
    private static final int MAX_FILE_TREE_SUMMARY_LENGTH = 160;
    private static final int MAX_FILE_TREE_RETRY_CONTEXT_CHARS = 18000;

    private static final String STEP2_SCENARIO_SPECS = "Step 2/5 - scenario_specs";
    private static final String STEP3_SUBSYSTEM_SUMMARIES = "Step 3/5 - subsystem_summaries";
    private static final String STEP4_API_DOCS = "Step 4/5 - api_docs";
    private static final String STEP5_FILE_TREE_DOCS = "Step 5/5 - file_tree_docs";

    private final RestClient claudeRestClient;
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final ArtifactService artifactService;
    private final RepoRunRepository repoRunRepository;
    private final LlmInputAssemblerService llmInputAssemblerService;
    private final LlmScenarioCacheService llmScenarioCacheService;

    private static final String PATH_REFINED_RULES = "llm/refined_rules.json";
    private static final String PATH_SCENARIO_SPECS = "llm/scenario_specs.json";
    private static final String PATH_SUBSYSTEM_SUMMARIES = "llm/subsystem_summaries.json";
    private static final String PATH_API_DOCS = "llm/api_docs.json";
    private static final String PATH_FILE_TREE_DOCS = "llm/file_tree_docs.json";

    private static final String PROMPT_REFINED_RULES_CHUNK = """
            역할: rule candidate를 간결한 정식 규칙으로 병합한다.
            강제 제약:
            1) 규칙은 최대 %d개까지만 반환한다.
            2) description은 짧은 1~2문장으로 작성한다.
            3) mergedFromGroups 길이는 3 이하로 제한한다.
            4) evidenceIds 길이는 5 이하로 제한한다.
            5) 정보 가치가 낮은 중복은 생략한다.
            작업:
            1) 중복/유사 후보를 병합한다.
            2) 간결한 규칙 이름을 작성한다.
            3) 각 규칙을 defensive 또는 domain으로 분류한다.
            4) 근거 연결(evidenceIds)을 유지한다.
            출력 JSON 스키마
            {"rules":[{"ruleId":"string","name":"string","classification":"defensive|domain",
            "description":"string","mergedFromGroups":["groupId"],"evidenceIds":[1],"confidence":0.0}]}
            """;

    private static final String PROMPT_SCENARIO_SPECS = """
            역할: 구조 분석 결과를 시나리오 명세(JSON)로 변환한다.
            반드시 지킬 규칙:
            1) 최상위 키는 scenarios 하나만 사용한다.
            2) schemaVersion/runId/repository/overview 같은 보조 키는 절대 출력하지 않는다.
            3) scenarios는 최대 8개만 출력한다.
            4) 각 scenario의 steps는 최대 4개만 출력한다.
            5) 각 step의 evidenceLinks는 최대 2개만 출력한다.
            6) description은 한 문장으로 140자 이내로 작성한다.
            7) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마:
            {"scenarios":[{"scenarioId":"SCN-001","title":"string","subsystem":"string",
            "steps":[{"stepNo":1,"description":"string",
            "evidenceLinks":[{"evidenceId":1,"filePath":"string","lines":"10-15"}]}]}]}
            """;

    private static final String PROMPT_SCENARIO_SPECS_COMPACT = """
            역할: 시나리오 명세를 토큰 절약 모드로 압축 생성한다.
            반드시 지킬 규칙:
            1) 최상위 키는 scenarios 하나만 사용한다.
            2) scenarios는 최대 4개만 출력한다.
            3) 각 scenario의 steps는 최대 3개만 출력한다.
            4) 각 step의 evidenceLinks는 최대 1개만 출력한다.
            5) description은 90자 이내로 간결하게 작성한다.
            6) 스키마 외 필드는 절대 출력하지 않는다.
            7) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마:
            {"scenarios":[{"scenarioId":"SCN-001","title":"string","subsystem":"string",
            "steps":[{"stepNo":1,"description":"string",
            "evidenceLinks":[{"evidenceId":1,"filePath":"string","lines":"10-15"}]}]}]}
            """;

    private static final String PROMPT_SUBSYSTEM_SUMMARIES = """
            역할: ranking/cluster/rule 문맥에서 subsystem 경계를 요약한다.
            작업:
            1) 간결한 subsystem 라벨을 작성한다.
            2) 책임을 한 문장으로 요약한다.
            3) topSymbols는 유효한 FQN만 사용한다.
            4) layer는 infrastructure/domain/application 중 하나로 지정한다.
            5) 최상위 키는 subsystems 하나만 사용한다.
            6) rules, scenarios, apiEntries, directories 키는 절대 출력하지 않는다.
            출력 JSON 스키마
            {"subsystems":[{"subsystemId":"string","label":"string","description":"string",
            "layer":"infrastructure|domain|application","topSymbols":["fqn1"],"ruleIds":["ruleId1"]}]}
            """;

    private static final String PROMPT_SUBSYSTEM_SUMMARIES_COMPACT = """
            역할: subsystem 요약을 토큰 절약 모드로 압축 생성한다.
            반드시 지킬 규칙:
            1) 최상위 키는 subsystems 하나만 사용한다.
            2) subsystems는 최대 10개만 출력한다.
            3) label/description은 짧은 문장으로 작성한다.
            4) 각 subsystem의 topSymbols는 최대 6개, ruleIds는 최대 8개만 출력한다.
            5) rules/scenarios/apiEntries/directories 키는 절대 출력하지 않는다.
            6) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마
            {"subsystems":[{"subsystemId":"string","label":"string","description":"string",
            "layer":"infrastructure|domain|application","topSymbols":["fqn1"],"ruleIds":["ruleId1"]}]}
            """;

    private static final String PROMPT_API_DOCS = """
            역할: 공개 API 인덱스를 생성한다.
            작업:
            1) 각 API의 목적을 summary 한 문장으로 작성한다.
            2) subsystem과 relatedScenarios를 연결한다.
            3) apiEntries는 최대 12개만 출력한다.
            4) 최상위 키는 apiEntries 하나만 사용한다.
            5) parameters/returns/throws/rules/scenarios/subsystems/directories 키는 절대 출력하지 않는다.
            출력 JSON 스키마
            {"apiEntries":[{"fqn":"string","summary":"string",
            "relatedScenarios":["scenarioId1"],"subsystem":"string"}]}
            """;

    private static final String PROMPT_API_DOCS_COMPACT = """
            역할: 공개 API 인덱스를 토큰 절약 모드로 압축 생성한다.
            반드시 지킬 규칙:
            1) 최상위 키는 apiEntries 하나만 사용한다.
            2) apiEntries는 최대 8개만 출력한다.
            3) summary는 80자 이내로 간결하게 작성한다.
            4) relatedScenarios는 최대 2개만 출력한다.
            5) parameters/returns/throws/rules/scenarios/subsystems/directories 키는 절대 출력하지 않는다.
            6) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마
            {"apiEntries":[{"fqn":"string","summary":"string",
            "relatedScenarios":["scenarioId1"],"subsystem":"string"}]}
            """;

    private static final String PROMPT_API_DOCS_MINI = """
            역할: 공개 API 인덱스를 최소 출력으로 생성한다.
            반드시 지킬 규칙:
            1) 최상위 키는 apiEntries 하나만 사용한다.
            2) apiEntries는 최대 6개만 출력한다.
            3) 각 entry는 fqn, summary, subsystem, relatedScenarios만 포함한다.
            4) summary는 60자 이내로 작성한다.
            5) relatedScenarios는 최대 1개만 포함한다.
            6) parameters/returns/throws/rules/scenarios/subsystems/directories 키는 절대 출력하지 않는다.
            7) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마
            {"apiEntries":[{"fqn":"string","summary":"string",
            "relatedScenarios":["scenarioId1"],"subsystem":"string"}]}
            """;

    private static final String PROMPT_FILE_TREE_DOCS = """
            역할: public API 전달 중심의 파일 트리 문서를 생성한다.
            반드시 지킬 규칙:
            1) 입력에 포함된 public 클래스/메서드만 문서화한다.
            2) private/protected/package 멤버는 생성하지 않는다.
            3) 테스트 또는 내부 구현 추정은 금지한다.
            4) 요약은 1~2문장으로 짧게 작성한다.
            5) 불확실하면 estimated=true로 표시한다.
            6) 최상위 키는 directories 하나만 사용한다.
            7) rules/scenarios/subsystems/apiEntries 키는 절대 출력하지 않는다.
            작업:
            1) directory -> file -> class -> method 계층으로 정리한다.
            2) 각 method는 relatedRules, relatedScenarios를 가능한 범위에서 연결한다.
            출력 JSON 스키마
            {"directories":[{"path":"src/main/java/...","files":[{"path":"...","classes":[
            {"symbolId":"...","name":"...","summary":"string","estimated":false,
            "methods":[{"symbolId":"...","name":"...","summary":"string","estimated":false,
            "relatedRules":["RULE-001"],"relatedScenarios":["SCN-001"]}]}]}]}]}
            """;

    private static final String PROMPT_FILE_TREE_DOCS_COMPACT = """
            역할: 파일 트리 문서를 토큰 절약 모드로 압축 생성한다.
            반드시 지킬 규칙:
            1) 최상위 키는 directories 하나만 사용한다.
            2) directories 최대 12개, 각 디렉터리 파일 최대 8개, 각 파일 클래스 최대 5개로 제한한다.
            3) 각 클래스 method는 최대 6개만 출력한다.
            4) summary는 짧게 작성하고 장문 설명을 금지한다.
            5) rules/scenarios/subsystems/apiEntries 키는 절대 출력하지 않는다.
            6) 마크다운/코드블록 없이 JSON만 출력한다.
            출력 JSON 스키마
            {"directories":[{"path":"src/main/java/...","files":[{"path":"...","classes":[
            {"symbolId":"...","name":"...","summary":"string","estimated":false,
            "methods":[{"symbolId":"...","name":"...","summary":"string","estimated":false,
            "relatedRules":["RULE-001"],"relatedScenarios":["SCN-001"]}]}]}]}]}
            """;

    /**
     * LLM 정제 파이프라인을 실행하고 아티팩트와 DB 캐시를 저장한다.
     */
    @Transactional
    public LlmResponse refine(LlmRequest request) {
        log.info("[LlmService] Refinement start. runId={}", request.getRunId());

        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new LlmException(LlmErrorCode.RUN_NOT_FOUND));

        LlmInputAssemblerService.LlmContextBundle contextBundle = llmInputAssemblerService.assemble(request);
        JsonNode structureNode = objectMapper.valueToTree(contextBundle.structureEngineOutput());

        // Step 1~4와 Step 5의 입력 구조를 분리해 누적 토큰을 줄인다.
        JsonNode generalStructure = buildGeneralStructureContext(structureNode);
        JsonNode fileTreeStructure = buildFileTreeOnlyContext(structureNode);

        List<LlmRequest.EvidenceSnippet> evidenceBundle = contextBundle.evidenceBundle() == null
                ? List.of()
                : contextBundle.evidenceBundle();
        String languageInstruction = buildLanguageInstruction(request.useKorean());

        // 1) 규칙 정제(분할/병합)
        JsonNode refinedRules = refineRulesWithChunkMerge(
                generalStructure,
                evidenceBundle,
                languageInstruction
        );
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_REFINED_RULES, ARTIFACT_SCHEMA_VERSION, PATH_REFINED_RULES, refinedRules
        );

        // Step1 이후에는 ruleCandidates 원문을 제거한 경량 컨텍스트를 사용한다.
        JsonNode postRefinementStructure = buildPostRefinementStructureContext(generalStructure);
        String generalContextWithEvidence = buildContext(postRefinementStructure, evidenceBundle);
        String generalContextWithoutEvidence = buildContext(postRefinementStructure, List.of());
        String fileTreeContextBase = buildContext(fileTreeStructure, List.of());

        // 2) 시나리오 생성 + DB 캐시 저장
        String scenarioContext = appendContextSection(
                generalContextWithEvidence,
                "정제 규칙",
                limitRefinedRulesForContext(refinedRules)
        );
        log.info("[LlmService] Step 2/5 - scenario_specs (contextChars={})", scenarioContext.length());
        JsonNode scenarioSpecs = generateScenarioSpecsWithRetry(scenarioContext, languageInstruction);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_SCENARIO_SPECS, ARTIFACT_SCHEMA_VERSION, PATH_SCENARIO_SPECS, scenarioSpecs
        );
        LlmScenarioCache scenarioCache = llmScenarioCacheService.upsertScenarioCache(
                run, scenarioSpecs, resolveHaikuModel(), SCENARIO_PROMPT_VERSION
        );

        // 3) 서브시스템 요약
        String subsystemContext = appendContextSection(
                appendContextSection(
                        generalContextWithoutEvidence,
                        "정제 규칙",
                        limitRefinedRulesForContext(refinedRules)
                ),
                "시나리오 명세",
                limitScenarioSpecsForContext(scenarioSpecs)
        );
        log.info("[LlmService] Step 3/5 - subsystem_summaries (contextChars={})", subsystemContext.length());
        JsonNode subsystemSummaries = generateSubsystemSummariesWithRetry(subsystemContext, languageInstruction);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_SUBSYSTEM_SUMMARIES, ARTIFACT_SCHEMA_VERSION, PATH_SUBSYSTEM_SUMMARIES, subsystemSummaries
        );

        // 4) API 문서
        String apiContext = appendContextSection(
                appendContextSection(
                        generalContextWithoutEvidence,
                        "서브시스템 요약",
                        limitSubsystemSummariesForContext(subsystemSummaries)
                ),
                "시나리오 명세",
                limitScenarioSpecsForContext(scenarioSpecs)
        );
        log.info("[LlmService] Step 4/5 - api_docs (contextChars={})", apiContext.length());
        JsonNode apiDocs = generateApiDocsWithRetry(apiContext, languageInstruction);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_API_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_API_DOCS, apiDocs
        );

        // 5) 파일 트리 기반 클래스/메서드 설명
        String fileTreeContext = appendContextSection(
                appendContextSection(
                        appendContextSection(
                                fileTreeContextBase,
                                "정제 규칙 요약",
                                compactRefinedRulesForFileTreeContext(refinedRules)
                        ),
                        "시나리오 요약",
                        compactScenarioSpecsForFileTreeContext(scenarioSpecs)
                ),
                "Public API 요약",
                compactApiDocsForFileTreeContext(apiDocs)
        );
        log.info("[LlmService] Step 5/5 - file_tree_docs (contextChars={})", fileTreeContext.length());
        JsonNode fileTreeDocs = generateFileTreeDocsWithRetry(fileTreeContext, languageInstruction);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.LLM_FILE_TREE_DOCS, ARTIFACT_SCHEMA_VERSION, PATH_FILE_TREE_DOCS, fileTreeDocs
        );

        log.info("[LlmService] Refinement complete. runId={}", request.getRunId());

        LlmResult llmResult = LlmResult.builder()
                .runId(request.getRunId())
                .refinedRules(refinedRules)
                .scenarioSpecs(scenarioSpecs)
                .subsystemSummaries(subsystemSummaries)
                .apiDocs(apiDocs)
                .fileTreeDocs(fileTreeDocs)
                .scenarioCacheId(scenarioCache.getCacheId())
                .build();

        return new LlmResponse(request.getRunId(), llmResult);
    }

    /**
     * 규칙 정제를 청크 단위로 수행하고 결과를 로컬에서 병합한다.
     */
    private JsonNode refineRulesWithChunkMerge(
            JsonNode generalStructure,
            List<LlmRequest.EvidenceSnippet> evidenceBundle,
            String languageInstruction
    ) {
        List<JsonNode> chunkedStructures = buildRuleCandidateChunks(generalStructure, REFINED_RULE_CHUNK_SIZE);
        if (chunkedStructures.isEmpty()) {
            chunkedStructures = List.of(generalStructure);
        }

        List<JsonNode> chunkRules = new ArrayList<>();
        int chunkIndex = 1;
        for (JsonNode chunkStructure : chunkedStructures) {
            Set<Long> chunkEvidenceIds = extractChunkEvidenceIds(chunkStructure);
            List<LlmRequest.EvidenceSnippet> filteredEvidence = filterEvidenceByIds(evidenceBundle, chunkEvidenceIds);
            String chunkContext = buildContext(chunkStructure, filteredEvidence);

            log.info(
                    "[LlmService] Step 1/5 - refined_rules chunk {}/{} (contextChars={}, evidenceCount={})",
                    chunkIndex,
                    chunkedStructures.size(),
                    chunkContext.length(),
                    filteredEvidence.size()
            );

            JsonNode chunkResponse = callClaude(
                    applyLanguagePolicy(buildRefinedRuleChunkPrompt(MAX_REFINED_RULES_PER_CHUNK), languageInstruction),
                    chunkContext,
                    MAX_TOKENS_REFINED_RULES_CHUNK
            );
            chunkRules.add(chunkResponse);
            chunkIndex++;
        }

        JsonNode merged = mergeRefinedRuleChunks(chunkRules);
        log.info(
                "[LlmService] Step 1/5 - refined_rules merged. chunkCount={}, mergedRules={}",
                chunkedStructures.size(),
                merged.path("rules").size()
        );
        return merged;
    }

    /**
     * ruleCandidates를 일정 개수 단위로 쪼개 Step1 입력/출력 토큰을 안정화한다.
     */
    private List<JsonNode> buildRuleCandidateChunks(JsonNode generalStructure, int chunkSize) {
        if (generalStructure == null || !generalStructure.isObject()) {
            return List.of();
        }

        JsonNode pipeline = generalStructure.path("pipeline");
        JsonNode ruleCandidates = pipeline.path("ruleCandidates");
        JsonNode candidates = ruleCandidates.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return List.of();
        }

        List<JsonNode> chunks = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, candidates.size());
            ObjectNode chunkRoot = ((ObjectNode) generalStructure).deepCopy();
            ObjectNode chunkRuleCandidates = chunkRoot.with("pipeline").with("ruleCandidates");
            ArrayNode chunkCandidates = objectMapper.createArrayNode();
            for (int idx = start; idx < end; idx++) {
                chunkCandidates.add(candidates.get(idx));
            }
            chunkRuleCandidates.set("candidates", chunkCandidates);
            chunkRuleCandidates.put("chunkStart", start);
            chunkRuleCandidates.put("chunkEndExclusive", end);
            chunkRuleCandidates.put("chunkCandidateCount", chunkCandidates.size());
            chunks.add(chunkRoot);
        }
        return chunks;
    }

    private Set<Long> extractChunkEvidenceIds(JsonNode chunkStructure) {
        Set<Long> ids = new HashSet<>();
        JsonNode candidates = chunkStructure.path("pipeline").path("ruleCandidates").path("candidates");
        if (!candidates.isArray()) {
            return ids;
        }
        for (JsonNode candidate : candidates) {
            JsonNode evidences = candidate.path("evidences");
            if (!evidences.isArray()) {
                continue;
            }
            for (JsonNode evidence : evidences) {
                JsonNode evidenceIdNode = evidence.get("evidenceId");
                if (evidenceIdNode != null && evidenceIdNode.canConvertToLong()) {
                    ids.add(evidenceIdNode.asLong());
                }
            }
        }
        return ids;
    }

    private List<LlmRequest.EvidenceSnippet> filterEvidenceByIds(
            List<LlmRequest.EvidenceSnippet> evidenceBundle,
            Set<Long> targetEvidenceIds
    ) {
        if (evidenceBundle == null || evidenceBundle.isEmpty()) {
            return List.of();
        }

        List<LlmRequest.EvidenceSnippet> result = new ArrayList<>();
        for (LlmRequest.EvidenceSnippet evidence : evidenceBundle) {
            if (evidence == null) {
                continue;
            }
            if (targetEvidenceIds.isEmpty()) {
                if (result.size() >= 24) {
                    break;
                }
                result.add(evidence);
                continue;
            }
            if (evidence.getEvidenceId() != null && targetEvidenceIds.contains(evidence.getEvidenceId())) {
                result.add(evidence);
            }
        }
        return result;
    }

    private String buildRefinedRuleChunkPrompt(int maxRulesPerChunk) {
        return String.format(PROMPT_REFINED_RULES_CHUNK, maxRulesPerChunk);
    }

    /**
     * 청크별 규칙 결과를 이름/분류 기준으로 병합하고 최종 ruleId를 부여한다.
     */
    private JsonNode mergeRefinedRuleChunks(List<JsonNode> chunkResponses) {
        Map<String, ObjectNode> mergedByKey = new LinkedHashMap<>();

        for (JsonNode response : chunkResponses) {
            JsonNode rules = response == null ? null : response.path("rules");
            if (rules == null || !rules.isArray()) {
                continue;
            }

            for (JsonNode ruleNode : rules) {
                if (!ruleNode.isObject()) {
                    continue;
                }
                ObjectNode rule = (ObjectNode) ruleNode.deepCopy();
                String key = buildRuleMergeKey(rule);

                if (!mergedByKey.containsKey(key)) {
                    mergedByKey.put(key, compactMergedRule(rule));
                    continue;
                }
                ObjectNode existing = mergedByKey.get(key);
                mergeRuleNode(existing, rule);
            }
        }

        List<ObjectNode> mergedList = new ArrayList<>(mergedByKey.values());
        mergedList.sort(Comparator.comparingDouble(this::extractConfidence).reversed());
        if (mergedList.size() > MAX_MERGED_REFINED_RULES) {
            mergedList = new ArrayList<>(mergedList.subList(0, MAX_MERGED_REFINED_RULES));
        }

        ArrayNode rulesOut = objectMapper.createArrayNode();
        int seq = 1;
        for (ObjectNode rule : mergedList) {
            rule.put("ruleId", String.format("RULE-%03d", seq++));
            rulesOut.add(rule);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("rules", rulesOut);
        result.put("chunkMergeApplied", true);
        result.put("ruleCount", rulesOut.size());
        return result;
    }

    private String buildRuleMergeKey(JsonNode rule) {
        String name = rule.path("name").asText("").trim().toLowerCase();
        String classification = rule.path("classification").asText("").trim().toLowerCase();
        return classification + "|" + name;
    }

    private ObjectNode compactMergedRule(ObjectNode rule) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("ruleId", rule.path("ruleId").asText(""));
        compact.put("name", rule.path("name").asText(""));
        compact.put("classification", rule.path("classification").asText(""));
        compact.put("description", shortenDescription(rule.path("description").asText("")));
        compact.set("mergedFromGroups", limitTextArray(rule.path("mergedFromGroups"), MAX_MERGED_GROUPS_PER_RULE));
        compact.set("evidenceIds", limitLongArray(rule.path("evidenceIds"), MAX_MERGED_EVIDENCE_IDS_PER_RULE));
        compact.put("confidence", roundConfidence(rule.path("confidence").asDouble(0.5d)));
        return compact;
    }

    private void mergeRuleNode(ObjectNode base, ObjectNode incoming) {
        base.set(
                "mergedFromGroups",
                mergeDistinctTextArrays(
                        base.path("mergedFromGroups"),
                        incoming.path("mergedFromGroups"),
                        MAX_MERGED_GROUPS_PER_RULE
                )
        );
        base.set(
                "evidenceIds",
                mergeDistinctLongArrays(
                        base.path("evidenceIds"),
                        incoming.path("evidenceIds"),
                        MAX_MERGED_EVIDENCE_IDS_PER_RULE
                )
        );

        double currentConfidence = base.path("confidence").asDouble(0.0d);
        double incomingConfidence = incoming.path("confidence").asDouble(0.0d);
        base.put("confidence", roundConfidence(Math.max(currentConfidence, incomingConfidence)));
    }

    private ArrayNode limitTextArray(JsonNode source, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return out;
        }
        for (int i = 0; i < source.size() && out.size() < limit; i++) {
            JsonNode item = source.get(i);
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private ArrayNode limitLongArray(JsonNode source, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return out;
        }
        for (int i = 0; i < source.size() && out.size() < limit; i++) {
            JsonNode item = source.get(i);
            if (item != null && item.canConvertToLong()) {
                out.add(item.asLong());
            }
        }
        return out;
    }

    private ArrayNode mergeDistinctTextArrays(JsonNode left, JsonNode right, int limit) {
        ArrayNode merged = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        appendDistinctText(left, merged, seen, limit);
        appendDistinctText(right, merged, seen, limit);
        return merged;
    }

    private ArrayNode mergeDistinctLongArrays(JsonNode left, JsonNode right, int limit) {
        ArrayNode merged = objectMapper.createArrayNode();
        Set<Long> seen = new HashSet<>();
        appendDistinctLong(left, merged, seen, limit);
        appendDistinctLong(right, merged, seen, limit);
        return merged;
    }

    private void appendDistinctText(JsonNode source, ArrayNode target, Set<String> seen, int limit) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            if (target.size() >= limit) {
                return;
            }
            if (item == null || !item.isTextual()) {
                continue;
            }
            String value = item.asText().trim();
            if (!value.isBlank() && seen.add(value)) {
                target.add(value);
            }
        }
    }

    private void appendDistinctLong(JsonNode source, ArrayNode target, Set<Long> seen, int limit) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            if (target.size() >= limit) {
                return;
            }
            if (item != null && item.canConvertToLong()) {
                long value = item.asLong();
                if (seen.add(value)) {
                    target.add(value);
                }
            }
        }
    }

    private double roundConfidence(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
        return rounded.doubleValue();
    }

    private double extractConfidence(ObjectNode rule) {
        return rule.path("confidence").asDouble(0.0d);
    }

    private String shortenDescription(String description) {
        if (description == null) {
            return "";
        }
        String value = description.trim();
        if (value.length() <= 120) {
            return value;
        }
        return value.substring(0, 120);
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

    private String firstNonBlankText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * Step2 시나리오 생성은 길이 초과가 잦아서 1차 실패 시 압축 모드로 1회 재시도한다.
     */
    private JsonNode generateScenarioSpecsWithRetry(String scenarioContext, String languageInstruction) {
        try {
            JsonNode scenarioSpecs = callClaudeWithHaikuFallback(
                    STEP2_SCENARIO_SPECS,
                    applyLanguagePolicy(PROMPT_SCENARIO_SPECS, languageInstruction),
                    scenarioContext,
                    MAX_TOKENS_SCENARIO_SPECS
            );
            return normalizeScenarioSpecs(scenarioSpecs);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmService] Step 2/5 scenario_specs parse failed. retrying with compact prompt/context."
            );
        }

        String compactContext = shortenText(scenarioContext, MAX_SCENARIO_RETRY_CONTEXT_CHARS);
        JsonNode compactScenarioSpecs = callClaudeWithHaikuFallback(
                STEP2_SCENARIO_SPECS + " compact",
                applyLanguagePolicy(PROMPT_SCENARIO_SPECS_COMPACT, languageInstruction),
                compactContext,
                MAX_TOKENS_SCENARIO_SPECS
        );
        return normalizeScenarioSpecs(compactScenarioSpecs);
    }

    /**
     * Step3 서브시스템 요약은 잘림/스키마 이탈 시 압축 모드로 1회 재시도한다.
     */
    private JsonNode generateSubsystemSummariesWithRetry(String subsystemContext, String languageInstruction) {
        try {
            JsonNode subsystemSummaries = callClaudeWithHaikuFallback(
                    STEP3_SUBSYSTEM_SUMMARIES,
                    applyLanguagePolicy(PROMPT_SUBSYSTEM_SUMMARIES, languageInstruction),
                    subsystemContext,
                    MAX_TOKENS_SUBSYSTEM_SUMMARIES
            );
            return normalizeSubsystemSummaries(subsystemSummaries);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmService] Step 3/5 subsystem_summaries parse failed. retrying with compact prompt/context."
            );
        }

        String compactContext = shortenText(subsystemContext, MAX_SUBSYSTEM_RETRY_CONTEXT_CHARS);
        JsonNode compactSubsystemSummaries = callClaudeWithHaikuFallback(
                STEP3_SUBSYSTEM_SUMMARIES + " compact",
                applyLanguagePolicy(PROMPT_SUBSYSTEM_SUMMARIES_COMPACT, languageInstruction),
                compactContext,
                MAX_TOKENS_SUBSYSTEM_SUMMARIES
        );
        return normalizeSubsystemSummaries(compactSubsystemSummaries);
    }

    /**
     * Step4 API 문서는 잘림/스키마 이탈 시 압축 모드로 1회 재시도한다.
     */
    private JsonNode generateApiDocsWithRetry(String apiContext, String languageInstruction) {
        try {
            JsonNode apiDocs = callClaudeWithHaikuFallback(
                    STEP4_API_DOCS,
                    applyLanguagePolicy(PROMPT_API_DOCS, languageInstruction),
                    apiContext,
                    MAX_TOKENS_API_DOCS
            );
            return normalizeApiDocs(apiDocs);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmService] Step 4/5 api_docs parse failed. retrying with compact prompt/context."
            );
        }

        String compactContext = shortenText(apiContext, MAX_API_RETRY_CONTEXT_CHARS);
        try {
            JsonNode compactApiDocs = callClaudeWithHaikuFallback(
                    STEP4_API_DOCS + " compact",
                    applyLanguagePolicy(PROMPT_API_DOCS_COMPACT, languageInstruction),
                    compactContext,
                    MAX_TOKENS_API_DOCS
            );
            return normalizeApiDocs(compactApiDocs);
        } catch (LlmException secondFailure) {
            if (!isResponseParseFailed(secondFailure)) {
                throw secondFailure;
            }
            log.warn(
                    "[LlmService] Step 4/5 api_docs compact parse failed. retrying with mini prompt/context."
            );
        }

        String miniContext = shortenText(apiContext, MAX_API_MINI_RETRY_CONTEXT_CHARS);
        JsonNode miniApiDocs = callClaudeWithHaikuFallback(
                STEP4_API_DOCS + " mini",
                applyLanguagePolicy(PROMPT_API_DOCS_MINI, languageInstruction),
                miniContext,
                MAX_TOKENS_API_DOCS
        );
        return normalizeApiDocs(miniApiDocs);
    }

    /**
     * Step5 파일 트리 문서는 잘림/스키마 이탈 시 압축 모드로 1회 재시도한다.
     */
    private JsonNode generateFileTreeDocsWithRetry(String fileTreeContext, String languageInstruction) {
        try {
            JsonNode fileTreeDocs = callClaudeWithHaikuFallback(
                    STEP5_FILE_TREE_DOCS,
                    applyLanguagePolicy(PROMPT_FILE_TREE_DOCS, languageInstruction),
                    fileTreeContext,
                    MAX_TOKENS_FILE_TREE_DOCS
            );
            return normalizeFileTreeDocs(fileTreeDocs);
        } catch (LlmException firstFailure) {
            if (!isResponseParseFailed(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "[LlmService] Step 5/5 file_tree_docs parse failed. retrying with compact prompt/context."
            );
        }

        String compactContext = shortenText(fileTreeContext, MAX_FILE_TREE_RETRY_CONTEXT_CHARS);
        JsonNode compactFileTreeDocs = callClaudeWithHaikuFallback(
                STEP5_FILE_TREE_DOCS + " compact",
                applyLanguagePolicy(PROMPT_FILE_TREE_DOCS_COMPACT, languageInstruction),
                compactContext,
                MAX_TOKENS_FILE_TREE_DOCS
        );
        return normalizeFileTreeDocs(compactFileTreeDocs);
    }

    /**
     * 시나리오 결과를 스키마 중심으로 정규화하여 후속 단계의 토큰 변동을 줄인다.
     */
    private JsonNode normalizeScenarioSpecs(JsonNode rawScenarioSpecs) {
        JsonNode scenariosNode = extractScenarioArray(rawScenarioSpecs);
        if (scenariosNode == null || !scenariosNode.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        ArrayNode scenariosOut = normalized.putArray("scenarios");

        for (int i = 0; i < scenariosNode.size() && i < MAX_SCENARIOS_OUTPUT; i++) {
            JsonNode scenario = scenariosNode.get(i);
            if (!scenario.isObject()) {
                continue;
            }

            ObjectNode scenarioOut = scenariosOut.addObject();
            String scenarioId = scenario.path("scenarioId").asText("").trim();
            if (scenarioId.isBlank()) {
                scenarioId = String.format("SCN-%03d", i + 1);
            }
            scenarioOut.put("scenarioId", scenarioId);
            scenarioOut.put("title", shortenText(scenario.path("title").asText(""), MAX_SCENARIO_TITLE_LENGTH));
            scenarioOut.put("subsystem", shortenText(scenario.path("subsystem").asText(""), MAX_SCENARIO_SUBSYSTEM_LENGTH));

            ArrayNode stepsOut = scenarioOut.putArray("steps");
            JsonNode steps = scenario.path("steps");
            if (steps.isArray()) {
                for (int stepIdx = 0;
                     stepIdx < steps.size() && stepIdx < MAX_STEPS_PER_SCENARIO_OUTPUT;
                     stepIdx++) {
                    JsonNode step = steps.get(stepIdx);
                    if (!step.isObject()) {
                        continue;
                    }

                    ObjectNode stepOut = stepsOut.addObject();
                    int stepNo = step.path("stepNo").asInt(stepIdx + 1);
                    stepOut.put("stepNo", stepNo > 0 ? stepNo : stepIdx + 1);

                    String description = shortenText(
                            step.path("description").asText(""),
                            MAX_SCENARIO_STEP_DESCRIPTION_LENGTH
                    );
                    if (description.isBlank()) {
                        description = "핵심 흐름 요약";
                    }
                    stepOut.put("description", description);

                    ArrayNode evidenceOut = stepOut.putArray("evidenceLinks");
                    JsonNode evidenceLinks = step.path("evidenceLinks");
                    if (evidenceLinks.isArray()) {
                        for (int linkIdx = 0;
                             linkIdx < evidenceLinks.size() && linkIdx < MAX_EVIDENCE_LINKS_PER_STEP_OUTPUT;
                             linkIdx++) {
                            JsonNode link = evidenceLinks.get(linkIdx);
                            if (!link.isObject()) {
                                continue;
                            }

                            ObjectNode linkOut = objectMapper.createObjectNode();
                            JsonNode evidenceIdNode = link.path("evidenceId");
                            if (evidenceIdNode.isNumber()) {
                                linkOut.put("evidenceId", evidenceIdNode.asLong());
                            }

                            String filePath = shortenText(link.path("filePath").asText(""), 200);
                            if (!filePath.isBlank()) {
                                linkOut.put("filePath", filePath);
                            }

                            String lines = shortenText(link.path("lines").asText(""), 40);
                            if (!lines.isBlank()) {
                                linkOut.put("lines", lines);
                            }

                            if (linkOut.size() > 0) {
                                evidenceOut.add(linkOut);
                            }
                        }
                    }
                }
            }

            if (stepsOut.size() == 0) {
                ObjectNode fallbackStep = stepsOut.addObject();
                fallbackStep.put("stepNo", 1);
                fallbackStep.put("description", "핵심 흐름 요약");
                fallbackStep.putArray("evidenceLinks");
            }
        }

        if (scenariosOut.size() == 0) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return normalized;
    }

    /**
     * subsystem_summaries 결과를 스키마 중심으로 정규화한다.
     */
    private JsonNode normalizeSubsystemSummaries(JsonNode rawSubsystemSummaries) {
        JsonNode subsystemsNode = extractArrayByKey(rawSubsystemSummaries, "subsystems");
        if (subsystemsNode == null || !subsystemsNode.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        ArrayNode subsystemsOut = normalized.putArray("subsystems");
        for (int i = 0; i < subsystemsNode.size() && i < MAX_SUBSYSTEMS_OUTPUT; i++) {
            JsonNode subsystem = subsystemsNode.get(i);
            if (!subsystem.isObject()) {
                continue;
            }

            ObjectNode out = subsystemsOut.addObject();
            String subsystemId = subsystem.path("subsystemId").asText("").trim();
            if (subsystemId.isBlank()) {
                subsystemId = String.format("ss_%03d", i + 1);
            }
            out.put("subsystemId", subsystemId);
            out.put("label", shortenText(subsystem.path("label").asText(""), MAX_SUBSYSTEM_LABEL_LENGTH));
            out.put("description", shortenText(subsystem.path("description").asText(""), MAX_SUBSYSTEM_DESCRIPTION_LENGTH));

            String layer = subsystem.path("layer").asText("").trim().toLowerCase();
            if (!"infrastructure".equals(layer) && !"domain".equals(layer) && !"application".equals(layer)) {
                layer = "application";
            }
            out.put("layer", layer);

            out.set("topSymbols", limitTextArray(subsystem.path("topSymbols"), MAX_TOP_SYMBOLS_PER_SUBSYSTEM_OUTPUT));
            out.set("ruleIds", limitTextArray(subsystem.path("ruleIds"), MAX_RULE_IDS_PER_SUBSYSTEM_OUTPUT));
        }

        if (subsystemsOut.size() == 0) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return normalized;
    }

    /**
     * api_docs 결과를 스키마 중심으로 정규화한다.
     */
    private JsonNode normalizeApiDocs(JsonNode rawApiDocs) {
        JsonNode entriesNode = extractArrayByKey(rawApiDocs, "apiEntries");
        if (entriesNode == null || !entriesNode.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        ArrayNode entriesOut = normalized.putArray("apiEntries");
        for (int i = 0; i < entriesNode.size() && i < MAX_API_ENTRIES_OUTPUT; i++) {
            JsonNode entry = entriesNode.get(i);
            if (!entry.isObject()) {
                continue;
            }

            String fqn = entry.path("fqn").asText("").trim();
            if (fqn.isBlank()) {
                continue;
            }

            ObjectNode out = entriesOut.addObject();
            out.put("fqn", shortenText(fqn, MAX_API_TEXT_LENGTH));
            String summary = firstNonBlankText(
                    entry.path("summary").asText(""),
                    entry.path("description").asText(""),
                    entry.path("title").asText("")
            );
            out.put("summary", shortenText(summary, MAX_API_TEXT_LENGTH));
            out.put("subsystem", shortenText(entry.path("subsystem").asText(""), 80));

            out.set(
                    "relatedScenarios",
                    limitTextArray(entry.path("relatedScenarios"), MAX_API_RELATED_SCENARIOS_PER_ENTRY_OUTPUT)
            );
        }

        if (entriesOut.size() == 0) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return normalized;
    }

    /**
     * file_tree_docs 결과를 스키마 중심으로 정규화한다.
     */
    private JsonNode normalizeFileTreeDocs(JsonNode rawFileTreeDocs) {
        JsonNode directoriesNode = extractArrayByKey(rawFileTreeDocs, "directories");
        if (directoriesNode == null || !directoriesNode.isArray()) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        ArrayNode directoriesOut = normalized.putArray("directories");
        for (int dirIdx = 0; dirIdx < directoriesNode.size() && dirIdx < MAX_FILE_TREE_DIRS_OUTPUT; dirIdx++) {
            JsonNode directory = directoriesNode.get(dirIdx);
            if (!directory.isObject()) {
                continue;
            }

            ObjectNode directoryOut = directoriesOut.addObject();
            directoryOut.put("path", shortenText(directory.path("path").asText(""), 240));

            ArrayNode filesOut = directoryOut.putArray("files");
            JsonNode files = directory.path("files");
            if (files.isArray()) {
                for (int fileIdx = 0; fileIdx < files.size() && fileIdx < MAX_FILES_PER_DIR_OUTPUT; fileIdx++) {
                    JsonNode file = files.get(fileIdx);
                    if (!file.isObject()) {
                        continue;
                    }

                    ObjectNode fileOut = filesOut.addObject();
                    fileOut.put("path", shortenText(file.path("path").asText(""), 260));

                    ArrayNode classesOut = fileOut.putArray("classes");
                    JsonNode classes = file.path("classes");
                    if (classes.isArray()) {
                        for (int classIdx = 0;
                             classIdx < classes.size() && classIdx < MAX_CLASSES_PER_FILE_OUTPUT;
                             classIdx++) {
                            JsonNode clazz = classes.get(classIdx);
                            if (!clazz.isObject()) {
                                continue;
                            }

                            ObjectNode classOut = classesOut.addObject();
                            classOut.put("symbolId", shortenText(clazz.path("symbolId").asText(""), 180));
                            classOut.put("name", shortenText(clazz.path("name").asText(""), 120));
                            classOut.put(
                                    "summary",
                                    shortenText(clazz.path("summary").asText(""), MAX_FILE_TREE_SUMMARY_LENGTH)
                            );
                            classOut.put("estimated", clazz.path("estimated").asBoolean(false));

                            ArrayNode methodsOut = classOut.putArray("methods");
                            JsonNode methods = clazz.path("methods");
                            if (methods.isArray()) {
                                for (int methodIdx = 0;
                                     methodIdx < methods.size() && methodIdx < MAX_METHODS_PER_CLASS_OUTPUT;
                                     methodIdx++) {
                                    JsonNode method = methods.get(methodIdx);
                                    if (!method.isObject()) {
                                        continue;
                                    }
                                    ObjectNode methodOut = methodsOut.addObject();
                                    methodOut.put("symbolId", shortenText(method.path("symbolId").asText(""), 180));
                                    methodOut.put("name", shortenText(method.path("name").asText(""), 120));
                                    methodOut.put(
                                            "summary",
                                            shortenText(method.path("summary").asText(""), MAX_FILE_TREE_SUMMARY_LENGTH)
                                    );
                                    methodOut.put("estimated", method.path("estimated").asBoolean(false));
                                    methodOut.set("relatedRules", limitTextArray(method.path("relatedRules"), 4));
                                    methodOut.set("relatedScenarios", limitTextArray(method.path("relatedScenarios"), 3));
                                }
                            }
                        }
                    }
                }
            }
        }

        if (directoriesOut.size() == 0) {
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
        return normalized;
    }

    private JsonNode extractScenarioArray(JsonNode rawScenarioSpecs) {
        if (rawScenarioSpecs == null || rawScenarioSpecs.isMissingNode() || rawScenarioSpecs.isNull()) {
            return null;
        }
        JsonNode scenarios = rawScenarioSpecs.path("scenarios");
        if (scenarios.isArray()) {
            return scenarios;
        }

        JsonNode nestedScenarios = rawScenarioSpecs.findValue("scenarios");
        if (nestedScenarios != null && nestedScenarios.isArray()) {
            return nestedScenarios;
        }
        return null;
    }

    private JsonNode extractArrayByKey(JsonNode raw, String key) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        JsonNode direct = raw.path(key);
        if (direct.isArray()) {
            return direct;
        }
        JsonNode nested = raw.findValue(key);
        if (nested != null && nested.isArray()) {
            return nested;
        }
        return null;
    }

    private boolean isResponseParseFailed(LlmException e) {
        return e != null && LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode());
    }

    /**
     * 모델 폴백은 일시 API 실패/파싱 실패일 때만 허용한다.
     */
    private boolean canFallbackToSonnet(LlmException e, String primaryModel, String fallbackModel) {
        if (e == null) {
            return false;
        }
        boolean fallbackError = LlmErrorCode.RESPONSE_PARSE_FAILED.equals(e.getCode())
                || LlmErrorCode.CLAUDE_API_CALL_FAILED.equals(e.getCode())
                || LlmErrorCode.CLAUDE_API_ERROR.equals(e.getCode());
        if (!fallbackError) {
            return false;
        }
        if (fallbackModel == null || fallbackModel.isBlank()) {
            return false;
        }
        return primaryModel == null || !fallbackModel.equalsIgnoreCase(primaryModel.trim());
    }

    /**
     * Haiku 모델이 비어 있으면 Sonnet 모델로 안전하게 대체한다.
     */
    private String resolveHaikuModel() {
        String haikuModel = llmConfig.getHaikuModel();
        if (haikuModel == null || haikuModel.isBlank()) {
            return llmConfig.getModel();
        }
        return haikuModel.trim();
    }

    private String sanitizeModel(String model) {
        if (model == null || model.isBlank()) {
            return llmConfig.getModel();
        }
        return model.trim();
    }

    /**
     * Claude API를 호출하고 JSON 응답을 파싱한다.
     */
    private JsonNode callClaude(String systemPrompt, String userMessage) {
        return callClaude(systemPrompt, userMessage, llmConfig.getMaxTokens());
    }

    /**
     * 단계별 출력 토큰 상한을 적용해 Claude를 호출한다.
     */
    private JsonNode callClaude(String systemPrompt, String userMessage, int maxTokens) {
        return callClaudeWithModel(systemPrompt, userMessage, maxTokens, llmConfig.getModel());
    }

    /**
     * Step2~5에서는 Haiku를 우선 호출하고 실패 시 Sonnet으로 1회 폴백한다.
     */
    private JsonNode callClaudeWithHaikuFallback(
            String stepName,
            String systemPrompt,
            String userMessage,
            int maxTokens
    ) {
        String primaryModel = resolveHaikuModel();
        String fallbackModel = llmConfig.getModel();

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

    /**
     * 지정한 모델로 Claude API를 호출한다.
     */
    private JsonNode callClaudeWithModel(
            String systemPrompt,
            String userMessage,
            int maxTokens,
            String model
    ) {
        int effectiveMaxTokens = Math.max(1, Math.min(maxTokens, llmConfig.getMaxTokens()));
        String effectiveModel = sanitizeModel(model);
        String requestBody = buildRequestBody(effectiveModel, systemPrompt, userMessage, effectiveMaxTokens);

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
                    log.error(
                            "[LlmService] Claude API call failed. model={}, status={}, message={}",
                            effectiveModel,
                            status,
                            e.getMessage()
                    );
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delayMillis = resolveRetryDelayMillis(e, attempt);
                log.warn(
                        "[LlmService] Claude API temporary failure. model={}, status={}, attempt={}/{}, retryDelayMs={}",
                        effectiveModel,
                        status,
                        attempt,
                        MAX_CLAUDE_RETRY_ATTEMPTS + 1,
                        delayMillis
                );
                sleepForRetry(delayMillis);
            } catch (Exception e) {
                if (attempt > MAX_CLAUDE_RETRY_ATTEMPTS) {
                    log.error("[LlmService] Claude API call failed. model={}, message={}", effectiveModel, e.getMessage());
                    throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
                }
                long delayMillis = Math.min(
                        BASE_RETRY_DELAY_MILLIS * (1L << Math.min(attempt - 1, 3)),
                        MAX_RETRY_DELAY_MILLIS
                );
                log.warn(
                        "[LlmService] Claude API transient error. model={}, attempt={}/{}, retryDelayMs={}, message={}",
                        effectiveModel,
                        attempt,
                        MAX_CLAUDE_RETRY_ATTEMPTS + 1,
                        delayMillis,
                        e.getMessage()
                );
                sleepForRetry(delayMillis);
            }
        }

        throw new LlmException(LlmErrorCode.CLAUDE_API_CALL_FAILED);
    }

    /**
     * Anthropic API 규격에 맞는 요청 JSON을 생성한다.
     */
    private String buildRequestBody(String model, String systemPrompt, String userMessage, int maxTokens) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("system", systemPrompt);

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMessageNode = messages.addObject();
            userMessageNode.put("role", "user");
            userMessageNode.put("content", userMessage);

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmErrorCode.REQUEST_SERIALIZE_FAILED);
        }
    }

    /**
     * Claude 응답(content[].text)을 JSON으로 파싱한다.
     */
    private JsonNode parseResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("error")) {
                String message = root.path("error").path("message").asText("unknown");
                log.error("[LlmService] Claude API error. message={}", message);
                throw new LlmException(LlmErrorCode.CLAUDE_API_ERROR);
            }
            String stopReason = root.path("stop_reason").asText("");

            String textContent = "";
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    textContent = block.path("text").asText("");
                    break;
                }
            }
            String jsonPayload = stripFence(textContent.trim());
            if (jsonPayload.isBlank()) {
                log.error("[LlmService] Claude response content is empty. stop_reason={}", stopReason);
                throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
            }

            try {
                return objectMapper.readTree(jsonPayload);
            } catch (Exception parseException) {
                if ("max_tokens".equalsIgnoreCase(stopReason)) {
                    log.warn("[LlmService] Claude response truncated by max_tokens.");
                }
                throw parseException;
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmService] Failed to parse Claude response. raw={}", raw);
            throw new LlmException(LlmErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * LLM이 markdown code fence로 감쌀 경우 fence를 제거한다.
     */
    private String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }

        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }

    /**
     * 구조 엔진 결과 + evidence bundle을 LLM 입력 텍스트로 조합한다.
     */
    private String buildContext(
            Object structureEngineOutput,
            Iterable<LlmRequest.EvidenceSnippet> evidenceBundle
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Structure ===\n");
        sb.append(toJson(structureEngineOutput));
        sb.append("\n\n=== Evidence ===\n");

        if (evidenceBundle != null) {
            for (LlmRequest.EvidenceSnippet evidence : evidenceBundle) {
                sb.append("[id=")
                        .append(evidence.getEvidenceId() == null ? "N/A" : evidence.getEvidenceId())
                        .append(",type=")
                        .append(evidence.getEvidenceType() == null ? "UNKNOWN" : evidence.getEvidenceType())
                        .append(",file=")
                        .append(evidence.getFilePath() == null ? "UNKNOWN" : evidence.getFilePath())
                        .append(",lines=")
                        .append(evidence.getStartLine() == null ? "?" : evidence.getStartLine())
                        .append("-")
                        .append(evidence.getEndLine() == null ? "?" : evidence.getEndLine())
                        .append("] ");
                sb.append(evidence.getSnippet() == null ? "" : evidence.getSnippet()).append("\n");
            }
        }

        return sb.toString();
    }

    private String appendContextSection(String baseContext, String title, Object content) {
        return baseContext + "\n\n--- " + title + " ---\n" + toJson(content);
    }

    // Step 1~4에서는 fileTreeSeed를 제외해 컨텍스트 중복을 줄인다.
    private JsonNode buildGeneralStructureContext(JsonNode structureNode) {
        if (structureNode == null || !structureNode.isObject()) {
            return structureNode;
        }
        ObjectNode copied = ((ObjectNode) structureNode).deepCopy();
        copied.remove("fileTreeSeed");
        JsonNode pipeline = copied.path("pipeline");
        if (pipeline.isObject()) {
            ((ObjectNode) pipeline).remove("fileTreeSeed");
        }
        return copied;
    }

    // Step 5는 파일 트리 문맥만 사용해 토큰 비용을 줄이고 전달 설명 생성에 집중한다.
    private JsonNode buildFileTreeOnlyContext(JsonNode structureNode) {
        ObjectNode context = objectMapper.createObjectNode();
        if (structureNode == null || !structureNode.isObject()) {
            return context;
        }

        if (structureNode.has("runId")) {
            context.set("runId", structureNode.get("runId"));
        }
        if (structureNode.has("generatedAt")) {
            context.set("generatedAt", structureNode.get("generatedAt"));
        }
        if (structureNode.has("language")) {
            context.set("language", structureNode.get("language"));
        }
        if (structureNode.has("qualityGate")) {
            context.set("qualityGate", structureNode.get("qualityGate"));
        }
        if (structureNode.has("displayHints")) {
            context.set("displayHints", structureNode.get("displayHints"));
        }

        JsonNode fileTreeSeed = structureNode.path("fileTreeSeed");
        if ((fileTreeSeed == null || fileTreeSeed.isMissingNode() || fileTreeSeed.isNull())
                && structureNode.path("pipeline").path("fileTreeSeed") != null) {
            fileTreeSeed = structureNode.path("pipeline").path("fileTreeSeed");
        }

        ObjectNode pipeline = context.putObject("pipeline");
        if (fileTreeSeed != null && !fileTreeSeed.isMissingNode() && !fileTreeSeed.isNull()) {
            pipeline.set("fileTreeSeed", fileTreeSeed);
        }
        return context;
    }

    /**
     * Step1 이후에는 대용량 원본 후보/엣지 컨텍스트를 걷어내어 Step2~4 입력을 경량화한다.
     */
    private JsonNode buildPostRefinementStructureContext(JsonNode generalStructure) {
        if (generalStructure == null || !generalStructure.isObject()) {
            return generalStructure;
        }
        ObjectNode copied = ((ObjectNode) generalStructure).deepCopy();
        JsonNode pipeline = copied.path("pipeline");
        if (!pipeline.isObject()) {
            return copied;
        }

        ObjectNode pipelineObj = (ObjectNode) pipeline;
        JsonNode ruleCandidates = pipelineObj.path("ruleCandidates");
        if (ruleCandidates.isObject()) {
            ((ObjectNode) ruleCandidates).remove("candidates");
        }

        JsonNode classDiagram = pipelineObj.path("classDiagram");
        if (classDiagram.isObject()) {
            ((ObjectNode) classDiagram).remove("edges");
        }
        return copied;
    }

    private JsonNode limitRefinedRulesForContext(JsonNode refinedRules) {
        ObjectNode limited = objectMapper.createObjectNode();
        JsonNode rules = refinedRules == null ? null : refinedRules.path("rules");
        limited.set("rules", takeFirst(rules, MAX_REFINED_RULES_FOR_CONTEXT));
        return limited;
    }

    private JsonNode limitScenarioSpecsForContext(JsonNode scenarioSpecs) {
        ObjectNode limited = objectMapper.createObjectNode();
        ArrayNode scenariosOut = objectMapper.createArrayNode();
        JsonNode scenarios = scenarioSpecs == null ? null : scenarioSpecs.path("scenarios");
        if (scenarios != null && scenarios.isArray()) {
            for (int i = 0; i < scenarios.size() && i < MAX_SCENARIOS_FOR_CONTEXT; i++) {
                JsonNode scenario = scenarios.get(i);
                JsonNode copied = scenario.deepCopy();
                if (copied.isObject()) {
                    ObjectNode scenarioObj = (ObjectNode) copied;
                    JsonNode steps = scenarioObj.path("steps");
                    if (steps.isArray()) {
                        scenarioObj.set("steps", takeFirst(steps, MAX_STEPS_PER_SCENARIO_FOR_CONTEXT));
                    }
                }
                scenariosOut.add(copied);
            }
        }
        limited.set("scenarios", scenariosOut);
        return limited;
    }

    private JsonNode limitSubsystemSummariesForContext(JsonNode subsystemSummaries) {
        ObjectNode limited = objectMapper.createObjectNode();
        JsonNode subsystems = subsystemSummaries == null ? null : subsystemSummaries.path("subsystems");
        limited.set("subsystems", takeFirst(subsystems, MAX_SUBSYSTEMS_FOR_CONTEXT));
        return limited;
    }

    private JsonNode limitApiDocsForContext(JsonNode apiDocs) {
        ObjectNode limited = objectMapper.createObjectNode();
        JsonNode entries = apiDocs == null ? null : apiDocs.path("apiEntries");
        limited.set("apiEntries", takeFirst(entries, MAX_API_ENTRIES_FOR_CONTEXT));
        return limited;
    }

    /**
     * Step5에서는 규칙 전체 대신 전달 메타(ruleId/name/classification)만 전달한다.
     */
    private JsonNode compactRefinedRulesForFileTreeContext(JsonNode refinedRules) {
        ObjectNode limited = objectMapper.createObjectNode();
        ArrayNode compactRules = objectMapper.createArrayNode();
        JsonNode rules = refinedRules == null ? null : refinedRules.path("rules");
        if (rules != null && rules.isArray()) {
            for (int i = 0; i < rules.size() && i < MAX_FILE_TREE_RULES_FOR_CONTEXT; i++) {
                JsonNode rule = rules.get(i);
                if (!rule.isObject()) {
                    continue;
                }
                ObjectNode compactRule = compactRules.addObject();
                compactRule.put("ruleId", rule.path("ruleId").asText(""));
                compactRule.put("name", rule.path("name").asText(""));
                compactRule.put("classification", rule.path("classification").asText(""));
                if (rule.has("confidence")) {
                    compactRule.put("confidence", rule.path("confidence").asDouble(0.0d));
                }
            }
        }
        limited.set("rules", compactRules);
        return limited;
    }

    /**
     * Step5에서는 시나리오를 전달 가능한 요약 형태로 줄이고 evidenceLinks 같은 장문 필드를 제거한다.
     */
    private JsonNode compactScenarioSpecsForFileTreeContext(JsonNode scenarioSpecs) {
        ObjectNode limited = objectMapper.createObjectNode();
        ArrayNode scenariosOut = objectMapper.createArrayNode();
        JsonNode scenarios = scenarioSpecs == null ? null : scenarioSpecs.path("scenarios");
        if (scenarios != null && scenarios.isArray()) {
            for (int i = 0; i < scenarios.size() && i < MAX_FILE_TREE_SCENARIOS_FOR_CONTEXT; i++) {
                JsonNode scenario = scenarios.get(i);
                if (!scenario.isObject()) {
                    continue;
                }

                ObjectNode scenarioOut = scenariosOut.addObject();
                scenarioOut.put("scenarioId", scenario.path("scenarioId").asText(""));
                scenarioOut.put("title", scenario.path("title").asText(""));
                scenarioOut.put("subsystem", scenario.path("subsystem").asText(""));

                ArrayNode stepsOut = scenarioOut.putArray("steps");
                JsonNode steps = scenario.path("steps");
                if (steps.isArray()) {
                    for (int stepIdx = 0;
                         stepIdx < steps.size() && stepIdx < MAX_FILE_TREE_STEPS_PER_SCENARIO;
                         stepIdx++) {
                        JsonNode step = steps.get(stepIdx);
                        ObjectNode stepOut = stepsOut.addObject();
                        stepOut.put("stepNo", step.path("stepNo").asInt(stepIdx + 1));
                        stepOut.put("description", shortenText(step.path("description").asText(""), 120));
                    }
                }
            }
        }
        limited.set("scenarios", scenariosOut);
        return limited;
    }

    /**
     * Step5에서는 public API 연결 목적의 최소 필드만 전달한다.
     */
    private JsonNode compactApiDocsForFileTreeContext(JsonNode apiDocs) {
        ObjectNode limited = objectMapper.createObjectNode();
        ArrayNode entriesOut = objectMapper.createArrayNode();
        JsonNode entries = apiDocs == null ? null : apiDocs.path("apiEntries");
        if (entries != null && entries.isArray()) {
            for (int i = 0; i < entries.size() && i < MAX_FILE_TREE_API_ENTRIES_FOR_CONTEXT; i++) {
                JsonNode entry = entries.get(i);
                if (!entry.isObject()) {
                    continue;
                }
                ObjectNode entryOut = entriesOut.addObject();
                entryOut.put("fqn", entry.path("fqn").asText(""));
                entryOut.put("summary", shortenText(entry.path("summary").asText(""), 140));
                entryOut.put("subsystem", entry.path("subsystem").asText(""));

                JsonNode relatedScenarios = entry.path("relatedScenarios");
                if (relatedScenarios.isArray()) {
                    entryOut.set("relatedScenarios", takeFirst(relatedScenarios, MAX_FILE_TREE_RELATED_SCENARIOS_PER_API));
                }
            }
        }
        limited.set("apiEntries", entriesOut);
        return limited;
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
                // retry-after 헤더가 초 단위 숫자가 아닌 경우 백오프 기본값을 사용
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

    /**
     * JSON은 유지하고 자연어는 지정된 언어로 출력되도록 프롬프트 언어 정책을 부착한다.
     */
    private String applyLanguagePolicy(String basePrompt, String languageInstruction) {
        return basePrompt + "\n\n언어 정책:\n" + languageInstruction
                + "\n- 마크다운은 금지하고, 유효한 JSON만 출력한다.";
    }

    private String buildLanguageInstruction(boolean preferKorean) {
        return "- JSON 형태는 요청 스키마를 정확히 준수한다.\n"
                + "- 자연어 필드(summary/description/title/condition 등)는 모두 한국어로 작성한다.\n"
                + "- 불확실한 내용은 추정 표시를 명시한다.";
    }

    /**
     * Object/JsonNode를 JSON 문자열로 변환한다.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}



