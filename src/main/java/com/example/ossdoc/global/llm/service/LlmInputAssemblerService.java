package com.example.ossdoc.global.llm.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.exception.LlmException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

/**
 * LLM 입력 조립 서비스.
 *
 * <p>팀 합의에 맞춰 LLM 입력 원천을 4개 JSON으로 제한한다.
 * - api_map.json (ArtifactKind.API_MAP_JSON)
 * - rule_candidates.json (ArtifactKind.RULE_CANDIDATES_JSON)
 * - rankings.json (ArtifactKind.RANKINGS_JSON)
 * - subsystems.json (ArtifactKind.SUBSYSTEMS_JSON)
 *
 * <p>입력이 부족하면 LLM에 더 많은 파일을 넣지 않고, 앞단 산출물 보강으로 해결하도록 설계한다.
 */
@Service
@RequiredArgsConstructor
public class LlmInputAssemblerService {

    private static final int MAX_AUTO_EVIDENCE = 28;
    private static final int MAX_CAUTIONS = 12;
    private static final int MAX_CORE_CLASSES = 12;
    private static final int MAX_CORE_METHODS = 24;
    private static final int MAX_METHODS_PER_CLASS = 6;
    private static final int MAX_METHOD_FLOW = 8;
    private static final int MAX_EXTENSION_POINTS = 10;
    private static final int MAX_DIRECTORIES = 12;
    private static final int MAX_EVIDENCE_PER_CAUTION = 2;
    private static final int MAX_SNIPPET_LENGTH = 180;

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    /**
     * LLM 실행에 필요한 구조 시드와 근거 번들을 만든다.
     */
    @Transactional(readOnly = true)
    public LlmContextBundle assemble(LlmRequest request) {
        if (!request.useAutoAssemble()) {
            return fromManualRequest(request);
        }
        try {
            ObjectNode structure = buildAutoStructure(request.getRunId(), request.useKorean());
            List<LlmRequest.EvidenceSnippet> evidenceBundle = resolveEvidenceBundle(
                    request,
                    structure.path("cautionSeed")
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> structureMap = objectMapper.convertValue(structure, Map.class);
            return new LlmContextBundle(structureMap, evidenceBundle);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(LlmErrorCode.CONTEXT_ASSEMBLE_FAILED);
        }
    }

    /**
     * 수동 입력 모드에서는 요청 JSON을 그대로 사용한다.
     */
    private LlmContextBundle fromManualRequest(LlmRequest request) {
        if (request.getStructureEngineOutput() == null || request.getStructureEngineOutput().isEmpty()) {
            throw new LlmException(LlmErrorCode.CONTEXT_ASSEMBLE_FAILED);
        }
        List<LlmRequest.EvidenceSnippet> evidence = request.getEvidenceBundle() == null
                ? List.of()
                : request.getEvidenceBundle();
        return new LlmContextBundle(
                request.getStructureEngineOutput(),
                trimEvidenceSnippets(evidence, MAX_AUTO_EVIDENCE)
        );
    }

    /**
     * 자동 조립: 4개 JSON만 읽어 구조 시드를 만든다.
     */
    private ObjectNode buildAutoStructure(String runId, boolean forceKorean) {
        JsonNode apiMap = loadOptionalArtifactMeta(runId, ArtifactKind.API_MAP_JSON);
        JsonNode ruleCandidates = requireArtifactMeta(runId, ArtifactKind.RULE_CANDIDATES_JSON);
        JsonNode rankings = requireArtifactMeta(runId, ArtifactKind.RANKINGS_JSON);
        JsonNode subsystems = requireArtifactMeta(runId, ArtifactKind.SUBSYSTEMS_JSON);

        List<CoreTypeSeed> coreTypes = extractCoreTypes(apiMap, rankings, subsystems);
        List<CoreMethodSeed> coreMethods = extractCoreMethods(apiMap, rankings, coreTypes);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("runId", runId);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("language", forceKorean ? "ko-KR" : "en-US");

        root.set("overviewSeed", buildOverviewSeed(runId, apiMap, rankings, subsystems));
        root.set("cautionSeed", buildCautionSeed(ruleCandidates, coreTypes, coreMethods));
        root.set("coreClassSeed", buildCoreClassSeed(coreTypes));
        root.set("coreMethodSeed", buildCoreMethodSeed(coreMethods));
        root.set("methodFlowSeed", buildMethodFlowSeed(apiMap, coreMethods));
        root.set("extensionSeed", buildExtensionSeed(apiMap, coreTypes, subsystems));
        root.set("directories", buildDirectories(apiMap, coreTypes, coreMethods));
        root.set("evidenceIndex", buildEvidenceIndex(coreTypes, coreMethods, ruleCandidates));
        root.set("qualityGate", buildQualityGate(apiMap, ruleCandidates, rankings, subsystems));

        // 하위 호환을 위한 집계 필드
        ObjectNode publicSurface = root.putObject("publicSurface");
        publicSurface.set("coreClasses", buildCoreClassSeed(coreTypes));
        publicSurface.set("coreMethods", buildCoreMethodSeed(coreMethods));
        publicSurface.set("extensionPoints", buildExtensionSeed(apiMap, coreTypes, subsystems));
        publicSurface.set("directories", buildDirectories(apiMap, coreTypes, coreMethods));
        publicSurface.set("apiEntries", buildApiEntries(coreMethods));

        return root;
    }

    /**
     * 개요 시드 생성.
     */
    private JsonNode buildOverviewSeed(String runId, JsonNode apiMap, JsonNode rankings, JsonNode subsystems) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("runId", runId);
        putIfText(out, "project", firstText(apiMap.path("overview"), "project", "name"));
        putIfText(out, "purpose", firstText(apiMap.path("overview"), "purpose"));
        putIfText(out, "fitSituation", firstText(apiMap.path("overview"), "fitSituation", "scope"));
        putIfText(out, "coreFeatures", firstText(apiMap.path("overview"), "coreFeatures"));
        putIfText(out, "repoUrl", firstText(apiMap.path("overview"), "repoUrl", "repositoryUrl"));

        int symbolRankCount = countArray(rankings.path("symbolRankings"));
        int subsystemCount = countArray(subsystems.path("subsystems"));
        out.put("symbolRankCount", symbolRankCount);
        out.put("subsystemCount", subsystemCount);
        out.put("apiMapPresent", !apiMap.isMissingNode() && !apiMap.isNull() && !apiMap.isEmpty());

        if (!out.hasNonNull("project")) {
            out.put("project", "오픈소스 프로젝트");
        }
        if (!out.hasNonNull("purpose")) {
            out.put("purpose", "핵심 API와 사용 순서를 빠르게 이해하도록 돕는다.");
        }
        if (!out.hasNonNull("fitSituation")) {
            out.put("fitSituation", "처음 라이브러리를 도입하거나 유지보수 중 구조를 빠르게 파악할 때");
        }
        if (!out.hasNonNull("coreFeatures")) {
            out.put("coreFeatures", "핵심 클래스/메서드, 시나리오, 주의사항, 확장 포인트 제공");
        }
        return out;
    }

    /**
     * 핵심 클래스 추출.
     */
    private List<CoreTypeSeed> extractCoreTypes(JsonNode apiMap, JsonNode rankings, JsonNode subsystems) {
        Map<String, CoreTypeSeed> byFqn = new LinkedHashMap<>();

        // 1) api_map 기반 (가장 신뢰)
        appendTypesFromApiMap(byFqn, apiMap.path("coreClasses"), 12);
        appendTypesFromApiMap(byFqn, apiMap.path("classes"), 10);
        appendTypesFromApiMap(byFqn, apiMap.path("types"), 8);

        // 2) rankings 기반 보강
        JsonNode symbolRankings = rankings.path("symbolRankings");
        if (symbolRankings.isArray()) {
            for (JsonNode rank : symbolRankings) {
                String qualifiedName = sanitizeQualifiedName(rank.path("qualifiedName").asText(""));
                if (qualifiedName.isBlank() || isMethodQualifiedName(qualifiedName)) {
                    continue;
                }
                CoreTypeSeed seed = byFqn.get(qualifiedName);
                int importance = scoreToImportance(rank.path("score").asDouble(0.0d), rank.path("apiScore").asDouble(0.0d));
                if (seed == null) {
                    byFqn.put(qualifiedName, new CoreTypeSeed(
                            rank.path("symbolId").asText(""),
                            qualifiedName,
                            extractPackageName(qualifiedName),
                            extractSimpleName(qualifiedName),
                            "",
                            inferClassRole(extractSimpleName(qualifiedName), qualifiedName),
                            inferClassUsage(extractSimpleName(qualifiedName), qualifiedName),
                            importance,
                            null,
                            null
                    ));
                } else if (importance > seed.importance()) {
                    byFqn.put(qualifiedName, seed.withImportance(importance));
                }
            }
        }

        // 3) subsystem entry/core symbol 반영
        Map<String, RankingSymbol> symbolById = indexRankingSymbols(symbolRankings);
        JsonNode subsystemList = subsystems.path("subsystems");
        if (subsystemList.isArray()) {
            for (JsonNode subsystem : subsystemList) {
                boostTypesBySymbolIds(byFqn, symbolById, subsystem.path("entrySymbolIds"), 2);
                boostTypesBySymbolIds(byFqn, symbolById, subsystem.path("coreSymbolIds"), 3);
            }
        }

        List<CoreTypeSeed> out = new ArrayList<>(byFqn.values());
        out.sort(Comparator.comparingInt(CoreTypeSeed::importance).reversed().thenComparing(CoreTypeSeed::fqn));
        if (out.size() > MAX_CORE_CLASSES) {
            return List.copyOf(out.subList(0, MAX_CORE_CLASSES));
        }
        return List.copyOf(out);
    }

    private void appendTypesFromApiMap(Map<String, CoreTypeSeed> byFqn, JsonNode array, int baseImportance) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String fqn = firstNonBlank(
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText(""),
                    item.path("classFqn").asText("")
            );
            fqn = sanitizeQualifiedName(fqn);
            if (fqn.isBlank() || isMethodQualifiedName(fqn)) {
                continue;
            }
            String className = firstNonBlank(item.path("className").asText(""), extractSimpleName(fqn));
            String packageName = firstNonBlank(item.path("packageName").asText(""), extractPackageName(fqn));
            String role = firstNonBlank(item.path("role").asText(""), inferClassRole(className, fqn));
            String usage = firstNonBlank(item.path("usage").asText(""), inferClassUsage(className, fqn));
            int importance = Math.max(baseImportance, item.path("importance").asInt(baseImportance));

            CoreTypeSeed prev = byFqn.get(fqn);
            CoreTypeSeed next = new CoreTypeSeed(
                    item.path("symbolId").asText(prev == null ? "" : prev.symbolId()),
                    fqn,
                    packageName,
                    className,
                    item.path("filePath").asText(prev == null ? "" : prev.filePath()),
                    role,
                    usage,
                    importance,
                    asNullableInt(item.path("startLine")),
                    asNullableInt(item.path("endLine"))
            );
            if (prev == null || next.importance() > prev.importance()) {
                byFqn.put(fqn, next);
            }
        }
    }

    private void boostTypesBySymbolIds(
            Map<String, CoreTypeSeed> byFqn,
            Map<String, RankingSymbol> symbolById,
            JsonNode symbolIds,
            int bonus
    ) {
        if (!symbolIds.isArray()) {
            return;
        }
        for (JsonNode idNode : symbolIds) {
            String symbolId = idNode.asText("");
            RankingSymbol ranking = symbolById.get(symbolId);
            if (ranking == null || ranking.qualifiedName().isBlank() || isMethodQualifiedName(ranking.qualifiedName())) {
                continue;
            }
            CoreTypeSeed seed = byFqn.get(ranking.qualifiedName());
            if (seed != null) {
                byFqn.put(ranking.qualifiedName(), seed.withImportance(seed.importance() + bonus));
            }
        }
    }

    /**
     * 핵심 메서드 추출.
     */
    private List<CoreMethodSeed> extractCoreMethods(
            JsonNode apiMap,
            JsonNode rankings,
            List<CoreTypeSeed> coreTypes
    ) {
        Set<String> coreTypeFqns = new HashSet<>();
        for (CoreTypeSeed type : coreTypes) {
            coreTypeFqns.add(type.fqn());
        }

        Map<String, CoreMethodSeed> byFqn = new LinkedHashMap<>();
        appendMethodsFromApiMap(byFqn, apiMap.path("coreMethods"), coreTypeFqns, 12);
        appendMethodsFromApiMap(byFqn, apiMap.path("methods"), coreTypeFqns, 10);
        appendMethodsFromApiMap(byFqn, apiMap.path("apiEntries"), coreTypeFqns, 9);

        JsonNode symbolRankings = rankings.path("symbolRankings");
        if (symbolRankings.isArray()) {
            for (JsonNode rank : symbolRankings) {
                String qualifiedName = sanitizeQualifiedName(rank.path("qualifiedName").asText(""));
                if (qualifiedName.isBlank() || !isMethodQualifiedName(qualifiedName)) {
                    continue;
                }
                String ownerFqn = extractOwnerFqn(qualifiedName);
                if (!coreTypeFqns.contains(ownerFqn)) {
                    continue;
                }

                String methodName = extractMethodName(qualifiedName);
                String methodFqn = toApiFqn(ownerFqn, methodName, "<init>".equals(methodName));
                int importance = scoreToImportance(rank.path("score").asDouble(0.0d), rank.path("apiScore").asDouble(0.0d));
                String className = extractSimpleName(ownerFqn);

                CoreMethodSeed prev = byFqn.get(methodFqn);
                CoreMethodSeed next = new CoreMethodSeed(
                        rank.path("symbolId").asText(prev == null ? "" : prev.symbolId()),
                        prev == null ? "" : prev.classSymbolId(),
                        ownerFqn,
                        className,
                        methodName,
                        methodFqn,
                        prev == null ? "" : prev.filePath(),
                        Math.max(importance, methodHeuristicBonus(methodName)),
                        prev == null ? "" : prev.signatureHint(),
                        inferMethodUsage(methodName, className, prev == null ? "" : prev.signatureHint()),
                        inferScenarioHint(methodName),
                        prev == null ? null : prev.startLine(),
                        prev == null ? null : prev.endLine()
                );
                if (prev == null || next.importance() > prev.importance()) {
                    byFqn.put(methodFqn, next);
                }
            }
        }

        List<CoreMethodSeed> out = new ArrayList<>(byFqn.values());
        out.sort(Comparator.comparingInt(CoreMethodSeed::importance).reversed().thenComparing(CoreMethodSeed::fqn));
        if (out.size() > MAX_CORE_METHODS) {
            return List.copyOf(out.subList(0, MAX_CORE_METHODS));
        }
        return List.copyOf(out);
    }

    private void appendMethodsFromApiMap(
            Map<String, CoreMethodSeed> byFqn,
            JsonNode array,
            Set<String> coreTypeFqns,
            int baseImportance
    ) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String methodFqn = firstNonBlank(
                    item.path("methodFqn").asText(""),
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText("")
            );
            String classFqn = firstNonBlank(item.path("classFqn").asText(""), extractOwnerFqn(methodFqn));
            methodFqn = sanitizeQualifiedName(methodFqn);
            classFqn = sanitizeQualifiedName(classFqn);

            if (methodFqn.isBlank() && !classFqn.isBlank()) {
                String methodNameFromField = firstNonBlank(item.path("methodName").asText(""), item.path("name").asText(""));
                if (!methodNameFromField.isBlank()) {
                    methodFqn = classFqn + "." + methodNameFromField;
                }
            }
            if (methodFqn.isBlank()) {
                continue;
            }
            if (!isMethodFqnShape(methodFqn)) {
                continue;
            }

            if (classFqn.isBlank()) {
                classFqn = extractOwnerFqn(methodFqn);
            }
            if (!classFqn.isBlank() && !coreTypeFqns.isEmpty() && !coreTypeFqns.contains(classFqn)) {
                continue;
            }

            String methodName = firstNonBlank(
                    item.path("methodName").asText(""),
                    item.path("name").asText(""),
                    extractMethodName(methodFqn)
            );
            String className = firstNonBlank(item.path("className").asText(""), extractSimpleName(classFqn));
            String signatureHint = firstNonBlank(item.path("signatureHint").asText(""), item.path("signature").asText(""));
            String summarySeed = firstNonBlank(
                    item.path("summary").asText(""),
                    inferMethodUsage(methodName, className, signatureHint)
            );
            int importance = Math.max(baseImportance, item.path("importance").asInt(baseImportance) + methodHeuristicBonus(methodName));

            CoreMethodSeed prev = byFqn.get(methodFqn);
            CoreMethodSeed next = new CoreMethodSeed(
                    item.path("symbolId").asText(prev == null ? "" : prev.symbolId()),
                    item.path("classSymbolId").asText(prev == null ? "" : prev.classSymbolId()),
                    classFqn,
                    className,
                    methodName,
                    methodFqn,
                    item.path("filePath").asText(prev == null ? "" : prev.filePath()),
                    importance,
                    signatureHint,
                    shortenText(summarySeed, 140),
                    firstNonBlank(item.path("scenarioHint").asText(""), inferScenarioHint(methodName)),
                    asNullableInt(item.path("startLine")),
                    asNullableInt(item.path("endLine"))
            );
            if (prev == null || next.importance() > prev.importance()) {
                byFqn.put(methodFqn, next);
            }
        }
    }

    /**
     * 규칙 후보를 사용자 주의사항 씨앗으로 변환한다.
     */
    private JsonNode buildCautionSeed(
            JsonNode ruleCandidates,
            List<CoreTypeSeed> coreTypes,
            List<CoreMethodSeed> coreMethods
    ) {
        Map<String, CoreTypeSeed> typeBySimple = new HashMap<>();
        for (CoreTypeSeed type : coreTypes) {
            typeBySimple.put(type.className().toLowerCase(Locale.ROOT), type);
        }

        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode cautions = out.putArray("cautions");
        JsonNode candidates = ruleCandidates.path("candidates");

        if (candidates.isArray()) {
            int seq = 1;
            for (int i = 0; i < candidates.size() && cautions.size() < MAX_CAUTIONS; i++) {
                JsonNode candidate = candidates.get(i);
                String title = firstNonBlank(
                        candidate.path("title").asText(""),
                        candidate.path("ruleKey").asText(""),
                        candidate.path("candidateKind").asText("")
                );
                if (title.isBlank()) {
                    continue;
                }

                ObjectNode caution = cautions.addObject();
                caution.put("cautionId", String.format("CAU-%03d", seq++));
                caution.put("title", shortenText(title, 70));
                caution.put("message", shortenText(firstNonBlank(
                        candidate.path("description").asText(""),
                        candidate.path("qualityReason").asText(""),
                        "호출 전 입력값과 호출 순서를 점검해야 합니다."
                ), 180));
                caution.put("classification", normalizeClassification(candidate.path("candidateKind").asText("")));
                caution.put("when", inferWhenText(candidate));

                ArrayNode evidenceIds = caution.putArray("evidenceIds");
                JsonNode evidences = candidate.path("evidences");
                if (evidences.isArray()) {
                    for (int e = 0; e < evidences.size() && e < MAX_EVIDENCE_PER_CAUTION; e++) {
                        JsonNode evidence = evidences.get(e);
                        if (evidence.path("evidenceId").canConvertToLong()) {
                            evidenceIds.add(evidence.path("evidenceId").asLong());
                        }
                    }
                }
                caution.put("confidence", round3(candidate.path("score").asDouble(0.72d)));

                String relatedClass = guessRelatedClass(candidate, typeBySimple);
                putIfText(caution, "relatedClass", relatedClass);
                putIfText(caution, "relatedMethod", guessRelatedMethod(candidate, coreMethods));
            }
        }

        if (cautions.isEmpty()) {
            ObjectNode caution = cautions.addObject();
            caution.put("cautionId", "CAU-001");
            caution.put("title", "입력 검증");
            caution.put("message", "필수 입력값이 누락되면 예외가 발생할 수 있으므로 호출 전 검증하세요.");
            caution.put("classification", "defensive");
            caution.put("when", "API 호출 전");
            caution.putArray("evidenceIds");
            caution.put("confidence", 0.6d);
        }

        out.put("count", cautions.size());
        return out;
    }

    private String inferWhenText(JsonNode candidate) {
        String kind = candidate.path("candidateKind").asText("").toLowerCase(Locale.ROOT);
        if (kind.contains("null") || kind.contains("guard") || kind.contains("require")) {
            return "메서드 호출 전 파라미터를 구성할 때";
        }
        if (kind.contains("state") || kind.contains("mutat")) {
            return "옵션/설정 값을 변경한 직후";
        }
        return "핵심 API 호출 흐름을 조합할 때";
    }

    private String guessRelatedClass(JsonNode candidate, Map<String, CoreTypeSeed> typeBySimple) {
        String text = (candidate.path("title").asText("") + " " + candidate.path("description").asText(""))
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<String, CoreTypeSeed> entry : typeBySimple.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue().fqn();
            }
        }
        return "";
    }

    private String guessRelatedMethod(JsonNode candidate, List<CoreMethodSeed> coreMethods) {
        String text = (candidate.path("title").asText("") + " " + candidate.path("description").asText(""))
                .toLowerCase(Locale.ROOT);
        for (CoreMethodSeed method : coreMethods) {
            if (text.contains(method.methodName().toLowerCase(Locale.ROOT))) {
                return method.fqn();
            }
        }
        return "";
    }

    /**
     * 클래스 시드 JSON.
     */
    private JsonNode buildCoreClassSeed(List<CoreTypeSeed> coreTypes) {
        ArrayNode out = objectMapper.createArrayNode();
        for (CoreTypeSeed type : coreTypes) {
            ObjectNode node = out.addObject();
            node.put("symbolId", type.symbolId());
            node.put("fqn", type.fqn());
            node.put("packageName", type.packageName());
            node.put("className", type.className());
            node.put("role", type.role());
            node.put("usage", type.usage());
            node.put("importance", type.importance());
            putIfText(node, "filePath", type.filePath());
            if (type.startLine() != null) {
                node.put("startLine", type.startLine());
            }
            if (type.endLine() != null) {
                node.put("endLine", type.endLine());
            }
        }
        return out;
    }

    /**
     * 메서드 시드 JSON.
     */
    private JsonNode buildCoreMethodSeed(List<CoreMethodSeed> coreMethods) {
        ArrayNode out = objectMapper.createArrayNode();
        for (CoreMethodSeed method : coreMethods) {
            ObjectNode node = out.addObject();
            node.put("symbolId", method.symbolId());
            node.put("classSymbolId", method.classSymbolId());
            node.put("classFqn", method.classFqn());
            node.put("className", method.className());
            node.put("methodName", method.methodName());
            node.put("fqn", method.fqn());
            node.put("signatureHint", method.signatureHint());
            node.put("summarySeed", method.summarySeed());
            node.put("scenarioHint", method.scenarioHint());
            node.put("importance", method.importance());
            putIfText(node, "filePath", method.filePath());
            if (method.startLine() != null) {
                node.put("startLine", method.startLine());
            }
            if (method.endLine() != null) {
                node.put("endLine", method.endLine());
            }
        }
        return out;
    }

    /**
     * 메서드 사용 순서 시드.
     */
    private JsonNode buildMethodFlowSeed(JsonNode apiMap, List<CoreMethodSeed> coreMethods) {
        // api_map에 순서 정보가 있으면 우선 사용
        JsonNode flowFromMap = firstArray(apiMap, "methodFlow", "methodUsageOrder");
        if (flowFromMap.isArray() && !flowFromMap.isEmpty()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (int i = 0; i < flowFromMap.size() && out.size() < MAX_METHOD_FLOW; i++) {
                JsonNode raw = flowFromMap.get(i);
                ObjectNode step = out.addObject();
                step.put("order", raw.path("order").asInt(i + 1));
                step.put("title", shortenText(raw.path("title").asText("단계"), 50));
                step.put("description", shortenText(raw.path("description").asText("핵심 메서드를 호출한다."), 120));
                putIfText(step, "classFqn", raw.path("classFqn").asText(""));
                putIfText(step, "methodFqn", firstNonBlank(raw.path("methodFqn").asText(""), raw.path("fqn").asText("")));
                putIfText(step, "filePath", raw.path("filePath").asText(""));
                if (raw.path("startLine").canConvertToInt()) {
                    step.put("startLine", raw.path("startLine").asInt());
                }
                if (raw.path("endLine").canConvertToInt()) {
                    step.put("endLine", raw.path("endLine").asInt());
                }
            }
            return out;
        }

        // 없으면 핵심 메서드 이름 패턴으로 유도
        ArrayNode flow = objectMapper.createArrayNode();
        int order = 1;
        order = appendFlowStep(flow, order, "옵션 정의", "필수/선택 옵션을 먼저 구성합니다.", findByKeyword(coreMethods, "add", "required", "builder", "set"));
        order = appendFlowStep(flow, order, "파싱 실행", "입력 인자를 파서에 전달해 결과 객체를 만듭니다.", findByKeyword(coreMethods, "parse", "build", "create"));
        order = appendFlowStep(flow, order, "결과 조회", "파싱 결과에서 옵션 존재 여부와 값을 확인합니다.", findByKeyword(coreMethods, "get", "has", "value"));
        appendFlowStep(flow, order, "도움말/예외 처리", "오류 상황에서는 도움말 출력 및 예외 처리를 연결합니다.", findByKeyword(coreMethods, "help", "print", "format"));
        return flow;
    }

    private CoreMethodSeed findByKeyword(List<CoreMethodSeed> methods, String... keywords) {
        for (CoreMethodSeed method : methods) {
            String lower = method.methodName().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return method;
                }
            }
        }
        return null;
    }

    private int appendFlowStep(ArrayNode flow, int order, String title, String description, CoreMethodSeed method) {
        ObjectNode step = flow.addObject();
        step.put("order", order);
        step.put("title", title);
        step.put("description", description);
        if (method != null) {
            step.put("classFqn", method.classFqn());
            step.put("methodFqn", method.fqn());
            putIfText(step, "filePath", method.filePath());
            if (method.startLine() != null) {
                step.put("startLine", method.startLine());
            }
            if (method.endLine() != null) {
                step.put("endLine", method.endLine());
            }
        }
        return order + 1;
    }

    /**
     * 확장 포인트 시드.
     */
    private JsonNode buildExtensionSeed(JsonNode apiMap, List<CoreTypeSeed> coreTypes, JsonNode subsystems) {
        ArrayNode out = objectMapper.createArrayNode();

        JsonNode extensionArray = firstArray(apiMap, "extensionPoints", "extensions");
        if (extensionArray.isArray()) {
            for (JsonNode item : extensionArray) {
                if (out.size() >= MAX_EXTENSION_POINTS) {
                    break;
                }
                ObjectNode node = out.addObject();
                putIfText(node, "symbolId", item.path("symbolId").asText(""));
                putIfText(node, "fqn", firstNonBlank(item.path("fqn").asText(""), item.path("classFqn").asText("")));
                putIfText(node, "className", firstNonBlank(item.path("className").asText(""), extractSimpleName(node.path("fqn").asText(""))));
                node.put("reason", firstNonBlank(item.path("reason").asText(""), "확장 가능 지점"));
                node.put("confidenceSource", firstNonBlank(item.path("confidenceSource").asText(""), "문서"));
                putIfText(node, "filePath", item.path("filePath").asText(""));
            }
        }

        if (out.size() < MAX_EXTENSION_POINTS) {
            for (CoreTypeSeed type : coreTypes) {
                if (out.size() >= MAX_EXTENSION_POINTS) {
                    break;
                }
                String reason = resolveExtensionReasonByName(type.className());
                if (reason.isBlank()) {
                    continue;
                }
                ObjectNode node = out.addObject();
                node.put("symbolId", type.symbolId());
                node.put("fqn", type.fqn());
                node.put("className", type.className());
                node.put("reason", reason);
                node.put("confidenceSource", reason.contains("추론") ? "추론" : "구조");
                putIfText(node, "filePath", type.filePath());
            }
        }

        // subsystem 패키지 루트 기반 보강
        JsonNode subsystemList = subsystems.path("subsystems");
        if (out.size() < MAX_EXTENSION_POINTS && subsystemList.isArray()) {
            for (JsonNode subsystem : subsystemList) {
                if (out.size() >= MAX_EXTENSION_POINTS) {
                    break;
                }
                JsonNode roots = subsystem.path("packageRoots");
                if (!roots.isArray() || roots.isEmpty()) {
                    continue;
                }
                ObjectNode node = out.addObject();
                node.put("symbolId", "");
                node.put("fqn", roots.get(0).asText(""));
                node.put("className", firstNonBlank(subsystem.path("name").asText(""), "Subsystem"));
                node.put("reason", "패키지 루트 기반 확장 후보(추론)");
                node.put("confidenceSource", "추론");
                node.put("filePath", "");
            }
        }

        return out;
    }

    private String resolveExtensionReasonByName(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("interface") || lower.contains("listener") || lower.contains("callback")) {
            return "인터페이스 기반 확장 지점";
        }
        if (lower.contains("abstract")) {
            return "추상 타입 기반 확장 지점";
        }
        if (lower.contains("provider") || lower.contains("factory") || lower.contains("builder")) {
            return "구조 기반 확장 후보(추론)";
        }
        return "";
    }

    /**
     * 디렉터리/클래스/메서드 시드.
     */
    private JsonNode buildDirectories(JsonNode apiMap, List<CoreTypeSeed> coreTypes, List<CoreMethodSeed> coreMethods) {
        JsonNode directoriesFromMap = apiMap.path("directories");
        if (directoriesFromMap.isArray() && !directoriesFromMap.isEmpty()) {
            ArrayNode trimmed = objectMapper.createArrayNode();
            for (int i = 0; i < directoriesFromMap.size() && trimmed.size() < MAX_DIRECTORIES; i++) {
                trimmed.add(directoriesFromMap.get(i));
            }
            return trimmed;
        }

        Map<String, List<CoreTypeSeed>> typesByDir = new LinkedHashMap<>();
        for (CoreTypeSeed type : coreTypes) {
            String dir = extractDirectoryPath(type.filePath());
            typesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(type);
        }

        Map<String, List<CoreMethodSeed>> methodsByClass = new HashMap<>();
        for (CoreMethodSeed method : coreMethods) {
            methodsByClass.computeIfAbsent(method.classFqn(), k -> new ArrayList<>()).add(method);
        }
        for (List<CoreMethodSeed> methods : methodsByClass.values()) {
            methods.sort(Comparator.comparingInt(CoreMethodSeed::importance).reversed());
        }

        ArrayNode directories = objectMapper.createArrayNode();
        int dirCount = 0;
        for (Map.Entry<String, List<CoreTypeSeed>> entry : typesByDir.entrySet()) {
            if (dirCount++ >= MAX_DIRECTORIES) {
                break;
            }
            ObjectNode dirNode = directories.addObject();
            dirNode.put("path", entry.getKey());
            ArrayNode files = dirNode.putArray("files");

            for (CoreTypeSeed type : entry.getValue()) {
                ObjectNode fileNode = files.addObject();
                fileNode.put("path", type.filePath());
                ArrayNode classes = fileNode.putArray("classes");
                ObjectNode classNode = classes.addObject();
                classNode.put("symbolId", type.symbolId());
                classNode.put("name", type.className());
                classNode.put("summary", type.role());
                classNode.put("estimated", false);

                ArrayNode methods = classNode.putArray("methods");
                List<CoreMethodSeed> classMethods = methodsByClass.getOrDefault(type.fqn(), List.of());
                for (int i = 0; i < classMethods.size() && i < MAX_METHODS_PER_CLASS; i++) {
                    CoreMethodSeed method = classMethods.get(i);
                    ObjectNode methodNode = methods.addObject();
                    methodNode.put("symbolId", method.symbolId());
                    methodNode.put("name", method.methodName());
                    methodNode.put("summary", method.summarySeed());
                    methodNode.put("estimated", false);
                }
            }
        }

        return directories;
    }

    /**
     * 근거 인덱스.
     */
    private JsonNode buildEvidenceIndex(
            List<CoreTypeSeed> coreTypes,
            List<CoreMethodSeed> coreMethods,
            JsonNode ruleCandidates
    ) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> dedupe = new LinkedHashSet<>();

        for (CoreTypeSeed type : coreTypes) {
            String key = "TYPE|" + type.fqn() + "|" + type.filePath() + "|" + type.startLine() + "|" + type.endLine();
            if (!dedupe.add(key)) {
                continue;
            }
            ObjectNode node = out.addObject();
            node.put("kind", "TYPE");
            putIfText(node, "symbolId", type.symbolId());
            putIfText(node, "fqn", type.fqn());
            putIfText(node, "filePath", type.filePath());
            if (type.startLine() != null) {
                node.put("startLine", type.startLine());
            }
            if (type.endLine() != null) {
                node.put("endLine", type.endLine());
            }
        }

        for (CoreMethodSeed method : coreMethods) {
            String key = "METHOD|" + method.fqn() + "|" + method.filePath() + "|" + method.startLine() + "|" + method.endLine();
            if (!dedupe.add(key)) {
                continue;
            }
            ObjectNode node = out.addObject();
            node.put("kind", "METHOD");
            putIfText(node, "symbolId", method.symbolId());
            putIfText(node, "fqn", method.fqn());
            putIfText(node, "filePath", method.filePath());
            if (method.startLine() != null) {
                node.put("startLine", method.startLine());
            }
            if (method.endLine() != null) {
                node.put("endLine", method.endLine());
            }
        }

        JsonNode candidates = ruleCandidates.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode evidences = candidate.path("evidences");
                if (!evidences.isArray()) {
                    continue;
                }
                for (JsonNode evidence : evidences) {
                    Long evidenceId = evidence.path("evidenceId").canConvertToLong() ? evidence.path("evidenceId").asLong() : null;
                    String filePath = safeText(evidence.path("filePath").asText(""));
                    Integer startLine = asNullableInt(evidence.path("startLine"));
                    Integer endLine = asNullableInt(evidence.path("endLine"));
                    String key = "RULE|" + evidenceId + "|" + filePath + "|" + startLine + "|" + endLine;
                    if (!dedupe.add(key)) {
                        continue;
                    }
                    ObjectNode node = out.addObject();
                    node.put("kind", "RULE_EVIDENCE");
                    if (evidenceId != null) {
                        node.put("evidenceId", evidenceId);
                    }
                    putIfText(node, "filePath", filePath);
                    if (startLine != null) {
                        node.put("startLine", startLine);
                    }
                    if (endLine != null) {
                        node.put("endLine", endLine);
                    }
                    putIfText(node, "snippet", trimSnippet(evidence.path("snippet").asText("")));
                }
            }
        }
        return out;
    }

    /**
     * 품질 게이트 요약.
     */
    private JsonNode buildQualityGate(JsonNode apiMap, JsonNode ruleCandidates, JsonNode rankings, JsonNode subsystems) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("apiMapPresent", !apiMap.isMissingNode() && !apiMap.isNull() && !apiMap.isEmpty());
        out.put("ruleCandidateCount", countArray(ruleCandidates.path("candidates")));
        out.put("rankingCount", countArray(rankings.path("symbolRankings")));
        out.put("subsystemCount", countArray(subsystems.path("subsystems")));
        out.put("inputSourcePolicy", "api_map + rule_candidates + rankings + subsystems");
        return out;
    }

    /**
     * UI 호환 API 엔트리 시드.
     */
    private JsonNode buildApiEntries(List<CoreMethodSeed> methods) {
        ArrayNode out = objectMapper.createArrayNode();
        for (CoreMethodSeed method : methods) {
            ObjectNode node = out.addObject();
            node.put("fqn", method.fqn());
            node.put("summary", method.summarySeed());
            node.put("subsystem", method.scenarioHint());
            node.putArray("relatedScenarios");
            putIfText(node, "filePath", method.filePath());
            if (method.startLine() != null) {
                node.put("startLine", method.startLine());
            }
            if (method.endLine() != null) {
                node.put("endLine", method.endLine());
            }
        }
        return out;
    }

    /**
     * 자동 근거 번들 생성.
     */
    private List<LlmRequest.EvidenceSnippet> resolveEvidenceBundle(LlmRequest request, JsonNode cautionSeed) {
        if (request.getEvidenceBundle() != null && !request.getEvidenceBundle().isEmpty()) {
            return trimEvidenceSnippets(request.getEvidenceBundle(), MAX_AUTO_EVIDENCE);
        }

        List<LlmRequest.EvidenceSnippet> out = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        JsonNode cautions = cautionSeed.path("cautions");
        if (cautions.isArray()) {
            for (JsonNode caution : cautions) {
                if (out.size() >= MAX_AUTO_EVIDENCE) {
                    break;
                }
                JsonNode ids = caution.path("evidenceIds");
                if (!ids.isArray()) {
                    continue;
                }
                for (JsonNode id : ids) {
                    if (out.size() >= MAX_AUTO_EVIDENCE || !id.canConvertToLong()) {
                        break;
                    }
                    Long evidenceId = id.asLong();
                    String key = "id:" + evidenceId;
                    if (!dedupe.add(key)) {
                        continue;
                    }
                    out.add(new LlmRequest.EvidenceSnippet(
                            evidenceId,
                            "",
                            null,
                            null,
                            "",
                            "RULE_EVIDENCE"
                    ));
                }
            }
        }
        return trimEvidenceSnippets(out, MAX_AUTO_EVIDENCE);
    }

    private List<LlmRequest.EvidenceSnippet> trimEvidenceSnippets(List<LlmRequest.EvidenceSnippet> evidences, int limit) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<LlmRequest.EvidenceSnippet> out = new ArrayList<>();
        for (LlmRequest.EvidenceSnippet evidence : evidences) {
            if (out.size() >= limit || evidence == null) {
                break;
            }
            out.add(new LlmRequest.EvidenceSnippet(
                    evidence.getEvidenceId(),
                    safeText(evidence.getFilePath()),
                    evidence.getStartLine(),
                    evidence.getEndLine(),
                    safeText(trimSnippet(evidence.getSnippet())),
                    safeText(evidence.getEvidenceType())
            ));
        }
        return List.copyOf(out);
    }

    private JsonNode requireArtifactMeta(String runId, ArtifactKind kind) {
        Artifact artifact = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind)
                .orElseThrow(() -> new LlmException(LlmErrorCode.REQUIRED_ARTIFACT_NOT_FOUND));
        if (artifact.getMeta() == null || artifact.getMeta().isNull()) {
            throw new LlmException(LlmErrorCode.REQUIRED_ARTIFACT_NOT_FOUND);
        }
        return artifact.getMeta();
    }

    private JsonNode loadOptionalArtifactMeta(String runId, ArtifactKind kind) {
        return artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind)
                .map(Artifact::getMeta)
                .orElse(NullNode.getInstance());
    }

    private int countArray(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private JsonNode firstArray(JsonNode node, String... keys) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return NullNode.getInstance();
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isArray() && !value.isEmpty()) {
                return value;
            }
        }
        return NullNode.getInstance();
    }

    private Map<String, RankingSymbol> indexRankingSymbols(JsonNode symbolRankings) {
        Map<String, RankingSymbol> out = new HashMap<>();
        if (!symbolRankings.isArray()) {
            return out;
        }
        for (JsonNode rank : symbolRankings) {
            String symbolId = rank.path("symbolId").asText("");
            if (symbolId.isBlank()) {
                continue;
            }
            out.put(symbolId, new RankingSymbol(
                    symbolId,
                    sanitizeQualifiedName(rank.path("qualifiedName").asText("")),
                    rank.path("score").asDouble(0.0d),
                    rank.path("apiScore").asDouble(0.0d)
            ));
        }
        return out;
    }

    private int scoreToImportance(double score, double apiScore) {
        double normalized = Math.max(score, 0.0d) + Math.max(apiScore, 0.0d) * 0.7d;
        int importance = (int) Math.round(normalized * 10.0d);
        return Math.max(1, Math.min(100, importance));
    }

    private String sanitizeQualifiedName(String qn) {
        String value = safeText(qn);
        if (value.startsWith("type:") || value.startsWith("method:")) {
            int idx = value.indexOf(':');
            if (idx >= 0 && idx + 1 < value.length()) {
                return value.substring(idx + 1);
            }
        }
        return value;
    }

    private boolean isMethodQualifiedName(String qn) {
        String value = safeText(qn);
        return value.contains("#") || value.contains("(");
    }

    private boolean isMethodFqnShape(String fqn) {
        String value = safeText(fqn);
        if (value.contains("#") || value.contains("(")) {
            return true;
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return false;
        }
        String tail = value.substring(idx + 1);
        return !tail.isBlank() && Character.isLowerCase(tail.charAt(0));
    }

    private String extractOwnerFqn(String methodQualified) {
        String value = safeText(methodQualified);
        if (value.contains("#")) {
            return value.substring(0, value.indexOf('#'));
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    private String extractMethodName(String methodQualified) {
        String value = safeText(methodQualified);
        if (value.contains("#")) {
            String tail = value.substring(value.indexOf('#') + 1);
            int p = tail.indexOf('(');
            return p >= 0 ? tail.substring(0, p) : tail;
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return value;
        }
        String tail = value.substring(idx + 1);
        int p = tail.indexOf('(');
        return p >= 0 ? tail.substring(0, p) : tail;
    }

    private String toApiFqn(String ownerFqn, String methodName, boolean constructor) {
        if (ownerFqn.isBlank()) {
            return methodName;
        }
        return constructor ? ownerFqn + ".<init>" : ownerFqn + "." + methodName;
    }

    private String extractPackageName(String fqn) {
        String value = safeText(fqn);
        int idx = value.lastIndexOf('.');
        if (idx <= 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    private String extractSimpleName(String fqn) {
        String value = safeText(fqn);
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return value;
        }
        return value.substring(idx + 1);
    }

    private String extractDirectoryPath(String filePath) {
        String normalized = safeText(filePath).replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return ".";
        }
        return normalized.substring(0, idx);
    }

    private String inferClassRole(String className, String fqn) {
        String lower = (className + " " + fqn).toLowerCase(Locale.ROOT);
        if (lower.contains("parser")) {
            return "입력 인자를 해석해 결과를 생성하는 핵심 파서 클래스";
        }
        if (lower.contains("commandline")) {
            return "파싱 결과를 조회하고 사용하는 결과 객체 클래스";
        }
        if (lower.contains("option")) {
            return "옵션 스키마를 정의하는 설정 클래스";
        }
        if (lower.contains("help") || lower.contains("formatter")) {
            return "사용법/도움말 출력을 담당하는 클래스";
        }
        return "공개 API 사용 흐름에서 핵심 동작을 담당하는 클래스";
    }

    private String inferClassUsage(String className, String fqn) {
        String lower = (className + " " + fqn).toLowerCase(Locale.ROOT);
        if (lower.contains("parser")) {
            return "옵션 정의 후 실제 args를 파싱할 때 사용";
        }
        if (lower.contains("option")) {
            return "애플리케이션 시작 시 옵션 규격을 정의할 때 사용";
        }
        if (lower.contains("help")) {
            return "오류 처리나 --help 출력 시 사용";
        }
        return "핵심 API 호출 흐름에서 기능을 조합할 때 사용";
    }

    private int methodHeuristicBonus(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        int bonus = 1;
        if (lower.contains("parse") || lower.contains("build")) {
            bonus += 4;
        }
        if (lower.contains("add") || lower.contains("required")) {
            bonus += 3;
        }
        if (lower.contains("get") || lower.contains("has")) {
            bonus += 2;
        }
        if (lower.contains("help") || lower.contains("print")) {
            bonus += 2;
        }
        return bonus;
    }

    private String inferMethodUsage(String methodName, String ownerClass, String signatureHint) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "입력 인자를 해석해 결과 객체를 생성할 때 호출한다.";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "옵션 스키마를 정의하거나 필수 옵션을 지정할 때 호출한다.";
        }
        if (lower.contains("get") || lower.contains("has")) {
            return "파싱 완료 후 옵션 값/존재 여부를 조회할 때 호출한다.";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "사용법 출력 또는 오류 안내를 보여줄 때 호출한다.";
        }
        if (!signatureHint.isBlank()) {
            return ownerClass + " 기능 사용 시 호출한다. 시그니처: " + shortenText(signatureHint, 80);
        }
        return ownerClass + " 기능 사용 시 호출한다.";
    }

    private String inferScenarioHint(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "SCN-파싱";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "SCN-도움말";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "SCN-옵션정의";
        }
        return "SCN-기본사용";
    }

    private String normalizeClassification(String kind) {
        String lower = safeText(kind).toLowerCase(Locale.ROOT);
        if (lower.contains("null") || lower.contains("guard") || lower.contains("require")) {
            return "defensive";
        }
        return "domain";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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

    private Integer asNullableInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.canConvertToInt() ? node.asInt() : null;
    }

    private String trimSnippet(String snippet) {
        if (snippet == null) {
            return null;
        }
        String normalized = snippet.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return shortenText(normalized, MAX_SNIPPET_LENGTH);
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

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    public record LlmContextBundle(
            Map<String, Object> structureEngineOutput,
            List<LlmRequest.EvidenceSnippet> evidenceBundle
    ) {
    }

    private record RankingSymbol(
            String symbolId,
            String qualifiedName,
            double score,
            double apiScore
    ) {
    }

    private record CoreTypeSeed(
            String symbolId,
            String fqn,
            String packageName,
            String className,
            String filePath,
            String role,
            String usage,
            int importance,
            Integer startLine,
            Integer endLine
    ) {
        private CoreTypeSeed withImportance(int nextImportance) {
            return new CoreTypeSeed(
                    symbolId,
                    fqn,
                    packageName,
                    className,
                    filePath,
                    role,
                    usage,
                    nextImportance,
                    startLine,
                    endLine
            );
        }
    }

    private record CoreMethodSeed(
            String symbolId,
            String classSymbolId,
            String classFqn,
            String className,
            String methodName,
            String fqn,
            String filePath,
            int importance,
            String signatureHint,
            String summarySeed,
            String scenarioHint,
            Integer startLine,
            Integer endLine
    ) {
    }
}
