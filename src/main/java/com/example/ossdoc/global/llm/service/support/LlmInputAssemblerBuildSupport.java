package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.model.CoreMethodSeed;
import com.example.ossdoc.global.llm.model.CoreTypeSeed;
import com.example.ossdoc.global.llm.model.RankingSymbol;
import com.example.ossdoc.global.llm.model.SourceAnchor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

import static com.example.ossdoc.global.llm.service.support.LlmInputAssemblerSupport.*;

/**
 * LlmInputAssemblerService의 구조 시드 생성/근거 조합 세부 로직을 분리한 지원 컴포넌트.
 * 서비스 본체는 아티팩트 조회와 흐름 제어만 담당한다.
 */
@Component
@RequiredArgsConstructor
public class LlmInputAssemblerBuildSupport {

    // 자동 조합 시 사용하는 출력 제한값
    private static final int MAX_AUTO_EVIDENCE = 60;
    private static final int MAX_CAUTIONS = 20;
    private static final int MAX_CORE_CLASSES = 24;
    private static final int MAX_CORE_METHODS = 48;
    private static final int MAX_METHODS_PER_CLASS = 10;
    private static final int MAX_METHOD_FLOW = 16;
    private static final int MAX_EXTENSION_POINTS = 20;
    private static final int MAX_DIRECTORIES = 24;
    private static final int MAX_EVIDENCE_PER_CAUTION = 5;

    private final ObjectMapper objectMapper;
    /**
     * symbol_source_index를 symbolId 기준으로 인덱싱한다.
     */
    public Map<String, SourceAnchor> indexSourceAnchorBySymbolId(JsonNode symbolSourceIndex) {
        Map<String, SourceAnchor> out = new HashMap<>();
        JsonNode symbols = symbolSourceIndex.path("symbols");
        if (!symbols.isArray()) {
            return out;
        }
        for (JsonNode item : symbols) {
            String symbolId = safeText(item.path("symbol_id").asText(""));
            if (symbolId.isBlank()) {
                continue;
            }
            String fqn = sanitizeQualifiedName(item.path("fqn").asText(""));
            String sourceFile = toUserFacingSourcePath(firstNonBlank(
                    item.path("source_file").asText(""),
                    item.path("sourceFile").asText("")
            ));
            Integer startLine = asNullableInt(item.path("start_line"));
            Integer endLine = asNullableInt(item.path("end_line"));
            double confidence = item.path("confidence").isNumber() ? item.path("confidence").asDouble(0.0d) : 0.0d;

            out.put(symbolId, new SourceAnchor(symbolId, fqn, sourceFile, startLine, endLine, confidence));
        }
        return out;
    }

    /**
     * symbol_source_index.json을 fqn 기준으로 인덱싱한다.
     * - 동일 fqn 충돌 시 confidence가 더 높은 항목을 채택한다.
     */
    /**
     * symbol_source_index를 FQN 기준으로 인덱싱한다.
     */
    public Map<String, SourceAnchor> indexSourceAnchorByFqn(JsonNode symbolSourceIndex) {
        Map<String, SourceAnchor> out = new HashMap<>();
        JsonNode symbols = symbolSourceIndex.path("symbols");
        if (!symbols.isArray()) {
            return out;
        }
        for (JsonNode item : symbols) {
            String fqn = sanitizeQualifiedName(item.path("fqn").asText(""));
            if (fqn.isBlank()) {
                continue;
            }
            String symbolId = safeText(item.path("symbol_id").asText(""));
            String sourceFile = toUserFacingSourcePath(firstNonBlank(
                    item.path("source_file").asText(""),
                    item.path("sourceFile").asText("")
            ));
            Integer startLine = asNullableInt(item.path("start_line"));
            Integer endLine = asNullableInt(item.path("end_line"));
            double confidence = item.path("confidence").isNumber() ? item.path("confidence").asDouble(0.0d) : 0.0d;

            SourceAnchor next = new SourceAnchor(symbolId, fqn, sourceFile, startLine, endLine, confidence);
            SourceAnchor prev = out.get(fqn);
            if (prev == null || next.confidence() > prev.confidence()) {
                out.put(fqn, next);
            }
        }
        return out;
    }

    /**
     * core class seed에 소스 앵커를 보강한다.
     */
    /**
     * core type seed에 source anchor를 보강한다.
     */
    public List<CoreTypeSeed> applyTypeSourceAnchors(
            List<CoreTypeSeed> coreTypes,
            Map<String, SourceAnchor> sourceAnchorBySymbolId,
            Map<String, SourceAnchor> sourceAnchorByFqn
    ) {
        if (coreTypes == null || coreTypes.isEmpty()) {
            return List.of();
        }
        List<CoreTypeSeed> out = new ArrayList<>(coreTypes.size());
        for (CoreTypeSeed seed : coreTypes) {
            SourceAnchor anchor = resolveBestAnchor(
                    seed.symbolId(),
                    seed.fqn(),
                    sourceAnchorBySymbolId,
                    sourceAnchorByFqn
            );
            if (anchor == null) {
                out.add(seed);
                continue;
            }
            out.add(new CoreTypeSeed(
                    seed.symbolId(),
                    seed.fqn(),
                    seed.packageName(),
                    seed.className(),
                    firstNonBlank(seed.filePath(), anchor.sourceFile()),
                    seed.role(),
                    seed.usage(),
                    seed.importance(),
                    firstNonNullInt(seed.startLine(), anchor.startLine()),
                    firstNonNullInt(seed.endLine(), anchor.endLine())
            ));
        }
        return List.copyOf(out);
    }

    /**
     * core method seed에 소스 앵커를 보강한다.
     */
    /**
     * core method seed에 source anchor를 보강한다.
     */
    public List<CoreMethodSeed> applyMethodSourceAnchors(
            List<CoreMethodSeed> coreMethods,
            Map<String, SourceAnchor> sourceAnchorBySymbolId,
            Map<String, SourceAnchor> sourceAnchorByFqn
    ) {
        if (coreMethods == null || coreMethods.isEmpty()) {
            return List.of();
        }
        List<CoreMethodSeed> out = new ArrayList<>(coreMethods.size());
        for (CoreMethodSeed seed : coreMethods) {
            SourceAnchor anchor = resolveBestAnchor(
                    seed.symbolId(),
                    seed.fqn(),
                    sourceAnchorBySymbolId,
                    sourceAnchorByFqn
            );
            if (anchor == null) {
                out.add(seed);
                continue;
            }
            out.add(new CoreMethodSeed(
                    seed.symbolId(),
                    seed.classSymbolId(),
                    seed.classFqn(),
                    seed.className(),
                    seed.methodName(),
                    seed.fqn(),
                    firstNonBlank(seed.filePath(), anchor.sourceFile()),
                    seed.importance(),
                    seed.signatureHint(),
                    seed.summarySeed(),
                    seed.scenarioHint(),
                    firstNonNullInt(seed.startLine(), anchor.startLine()),
                    firstNonNullInt(seed.endLine(), anchor.endLine())
            ));
        }
        return List.copyOf(out);
    }

    private SourceAnchor resolveBestAnchor(
            String symbolId,
            String fqn,
            Map<String, SourceAnchor> sourceAnchorBySymbolId,
            Map<String, SourceAnchor> sourceAnchorByFqn
    ) {
        SourceAnchor byId = sourceAnchorBySymbolId.get(safeText(symbolId));
        if (byId != null) {
            return byId;
        }
        return sourceAnchorByFqn.get(sanitizeQualifiedName(fqn));
    }

    /**
     * 개요 시드 생성.
     */
    /**
     * 프로젝트 개요 seed를 생성한다.
     */
    public JsonNode buildOverviewSeed(String runId, JsonNode apiMap, JsonNode rankings, JsonNode subsystems) {
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
    /**
     * api_map/rankings/subsystems를 합쳐 핵심 타입 목록을 추출한다.
     */
    public List<CoreTypeSeed> extractCoreTypes(
            JsonNode apiMap,
            JsonNode rankings,
            JsonNode subsystems,
            Map<String, String> publicApiTypeBySymbolId
    ) {
        Map<String, CoreTypeSeed> byFqn = new LinkedHashMap<>();

        // 1) api_map 기반 (가장 신뢰)
        appendTypesFromApiMap(byFqn, apiMap.path("coreClasses"), 12, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("classes"), 10, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("types"), 8, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("entry_points"), 12, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("entryPoints"), 12, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("extension_points"), 10, publicApiTypeBySymbolId);
        appendTypesFromApiMap(byFqn, apiMap.path("extensionPoints"), 10, publicApiTypeBySymbolId);

        // 2) rankings 기반 보강
        JsonNode symbolRankings = rankings.path("symbolRankings");
        if (symbolRankings.isArray()) {
            for (JsonNode rank : symbolRankings) {
                String qualifiedName = resolveTypeQualifiedName(rank);
                if (qualifiedName.isBlank()) {
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

    private void appendTypesFromApiMap(
            Map<String, CoreTypeSeed> byFqn,
            JsonNode array,
            int baseImportance,
            Map<String, String> publicApiTypeBySymbolId
    ) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String symbolId = firstNonBlank(
                    item.path("symbolId").asText(""),
                    item.path("symbol_id").asText("")
            );
            String fqn = firstNonBlank(
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText(""),
                    item.path("qualified_name").asText(""),
                    item.path("classFqn").asText(""),
                    item.path("class_fqn").asText("")
            );
            if (fqn.isBlank() && symbolId != null && !symbolId.isBlank()) {
                fqn = firstNonBlank(
                        publicApiTypeBySymbolId.get(symbolId),
                        symbolId.startsWith("type:") ? sanitizeQualifiedName(symbolId) : ""
                );
            }
            fqn = sanitizeQualifiedName(fqn);
            if (fqn.isBlank() || isMethodQualifiedName(fqn)) {
                continue;
            }
            String className = firstNonBlank(
                    item.path("className").asText(""),
                    item.path("simpleName").asText(""),
                    item.path("simple_name").asText(""),
                    extractSimpleName(fqn)
            );
            String packageName = firstNonBlank(
                    item.path("packageName").asText(""),
                    item.path("package_name").asText(""),
                    extractPackageName(fqn)
            );
            String role = firstNonBlank(item.path("role").asText(""), inferClassRole(className, fqn));
            String usage = firstNonBlank(item.path("usage").asText(""), inferClassUsage(className, fqn));
            int score = item.path("score").asInt(0);
            int confidenceBoost = confidenceToImportanceBoost(item.path("confidence").asText(""));
            int importance = Math.max(baseImportance, item.path("importance").asInt(baseImportance));
            importance = Math.max(importance, baseImportance + confidenceBoost + Math.min(4, Math.max(0, score / 2)));
            String resolvedFilePath = toUserFacingSourcePath(firstNonBlank(
                    item.path("filePath").asText(""),
                    item.path("file_path").asText(""),
                    item.path("sourceFile").asText(""),
                    item.path("source_file").asText("")
            ));

            CoreTypeSeed prev = byFqn.get(fqn);
            CoreTypeSeed next = new CoreTypeSeed(
                    firstNonBlank(symbolId, prev == null ? "" : prev.symbolId()),
                    fqn,
                    packageName,
                    className,
                    firstNonBlank(
                            resolvedFilePath,
                            prev == null ? "" : prev.filePath()
                    ),
                    role,
                    usage,
                    importance,
                    firstNonNullInt(asNullableInt(item.path("startLine")), asNullableInt(item.path("start_line"))),
                    firstNonNullInt(asNullableInt(item.path("endLine")), asNullableInt(item.path("end_line")))
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
    /**
     * api_map/rankings/rule_candidates를 합쳐 핵심 메서드 목록을 추출한다.
     */
    public List<CoreMethodSeed> extractCoreMethods(
            JsonNode apiMap,
            JsonNode rankings,
            List<CoreTypeSeed> coreTypes,
            JsonNode ruleCandidates,
            Map<String, String> publicApiTypeBySymbolId
    ) {
        Set<String> coreTypeFqns = new HashSet<>();
        for (CoreTypeSeed type : coreTypes) {
            coreTypeFqns.add(type.fqn());
        }

        Map<String, CoreMethodSeed> byFqn = new LinkedHashMap<>();
        appendMethodsFromApiMap(byFqn, apiMap.path("coreMethods"), coreTypeFqns, 12);
        appendMethodsFromApiMap(byFqn, apiMap.path("core_methods"), coreTypeFqns, 12);
        appendMethodsFromApiMap(byFqn, apiMap.path("methods"), coreTypeFqns, 10);
        appendMethodsFromApiMap(byFqn, apiMap.path("apiEntries"), coreTypeFqns, 9);
        appendMethodsFromApiMap(byFqn, apiMap.path("api_entries"), coreTypeFqns, 9);
        appendMethodsFromApiMap(byFqn, firstArray(apiMap, "methodFlow", "methodUsageOrder", "method_flow", "method_usage_order"), coreTypeFqns, 11);

        JsonNode symbolRankings = rankings.path("symbolRankings");
        if (symbolRankings.isArray()) {
            for (JsonNode rank : symbolRankings) {
                String qualifiedName = resolveMethodQualifiedName(rank);
                if (qualifiedName.isBlank()) {
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
                        normalizeSummarySeed(inferMethodUsage(methodName, className, prev == null ? "" : prev.signatureHint())),
                        inferScenarioHint(methodName),
                        prev == null ? null : prev.startLine(),
                        prev == null ? null : prev.endLine()
                );
                if (prev == null || next.importance() > prev.importance()) {
                    byFqn.put(methodFqn, next);
                }
            }
        }

        if (byFqn.isEmpty()) {
            // rule_candidates의 method_cards를 먼저 반영해 "규칙 후보가 없는 메서드"도 코어 메서드 씨앗으로 포함한다.
            appendMethodsFromApiMap(byFqn, ruleCandidates.path("methodCards"), coreTypeFqns, 9);
            appendMethodsFromApiMap(byFqn, ruleCandidates.path("method_cards"), coreTypeFqns, 9);
            appendMethodsFromRuleCandidates(byFqn, ruleCandidates, coreTypeFqns, 8, publicApiTypeBySymbolId);
        } else {
            // 이미 seed가 있더라도 method_cards를 낮은 가중치로 합쳐 coverage를 넓힌다.
            appendMethodsFromApiMap(byFqn, ruleCandidates.path("methodCards"), coreTypeFqns, 7);
            appendMethodsFromApiMap(byFqn, ruleCandidates.path("method_cards"), coreTypeFqns, 7);
            appendMethodsFromRuleCandidates(byFqn, ruleCandidates, coreTypeFqns, 6, publicApiTypeBySymbolId);
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
                    item.path("method_fqn").asText(""),
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText(""),
                    item.path("qualified_name").asText("")
            );
            String classFqn = firstNonBlank(
                    item.path("classFqn").asText(""),
                    item.path("class_fqn").asText(""),
                    extractOwnerFqn(methodFqn)
            );
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
                    item.path("method_name").asText(""),
                    item.path("name").asText(""),
                    extractMethodName(methodFqn)
            );
            methodFqn = toApiFqn(
                    firstNonBlank(classFqn, extractOwnerFqn(methodFqn)),
                    methodName,
                    "<init>".equals(methodName)
            );
            String className = firstNonBlank(item.path("className").asText(""), extractSimpleName(classFqn));
            if (className.isBlank()) {
                className = firstNonBlank(item.path("class_name").asText(""), extractSimpleName(classFqn));
            }
            String signatureHint = firstNonBlank(item.path("signatureHint").asText(""), item.path("signature").asText(""));
            String summarySeed = firstNonBlank(
                    item.path("summary").asText(""),
                    item.path("summaryHint").asText(""),
                    item.path("summary_hint").asText(""),
                    inferMethodUsage(methodName, className, signatureHint)
            );
            int importance = Math.max(baseImportance, item.path("importance").asInt(baseImportance) + methodHeuristicBonus(methodName));

            CoreMethodSeed prev = byFqn.get(methodFqn);
            CoreMethodSeed next = new CoreMethodSeed(
                    firstNonBlank(
                            item.path("symbolId").asText(""),
                            item.path("symbol_id").asText(""),
                            item.path("methodSymbolId").asText(""),
                            item.path("method_symbol_id").asText(""),
                            prev == null ? "" : prev.symbolId()
                    ),
                    firstNonBlank(
                            item.path("classSymbolId").asText(""),
                            item.path("class_symbol_id").asText(""),
                            prev == null ? "" : prev.classSymbolId()
                    ),
                    classFqn,
                    className,
                    methodName,
                    methodFqn,
                    firstNonBlank(
                            toUserFacingSourcePath(firstNonBlank(
                                    item.path("filePath").asText(""),
                                    item.path("file_path").asText(""),
                                    item.path("sourceFile").asText(""),
                                    item.path("source_file").asText("")
                            )),
                            prev == null ? "" : prev.filePath()
                    ),
                    importance,
                    signatureHint,
                    normalizeSummarySeed(summarySeed),
                    firstNonBlank(
                            item.path("scenarioHint").asText(""),
                            item.path("scenario_hint").asText(""),
                            inferScenarioHint(methodName)
                    ),
                    firstNonNullInt(asNullableInt(item.path("startLine")), asNullableInt(item.path("start_line"))),
                    firstNonNullInt(asNullableInt(item.path("endLine")), asNullableInt(item.path("end_line")))
            );
            if (prev == null || next.importance() > prev.importance()) {
                byFqn.put(methodFqn, next);
            }
        }
    }

    private void appendMethodsFromRuleCandidates(
            Map<String, CoreMethodSeed> byFqn,
            JsonNode ruleCandidates,
            Set<String> coreTypeFqns,
            int baseImportance,
            Map<String, String> publicApiTypeBySymbolId
    ) {
        JsonNode candidates = ruleCandidates.path("candidates");
        if (!candidates.isArray()) {
            return;
        }
        for (JsonNode candidate : candidates) {
            String subjectSymbolId = safeText(candidate.path("subjectSymbolId").asText(""));
            String methodQualifiedName = sanitizeQualifiedName(candidate.path("subjectQualifiedName").asText(""));
            if (methodQualifiedName.isBlank()) {
                methodQualifiedName = methodQualifiedNameFromSymbolId(subjectSymbolId);
            }
            if (methodQualifiedName.isBlank() || !isMethodQualifiedName(methodQualifiedName)) {
                continue;
            }

            String ownerFqn = extractOwnerFqn(methodQualifiedName);
            ownerFqn = sanitizeQualifiedName(ownerFqn);
            if (ownerFqn.isBlank()) {
                continue;
            }

            if (!coreTypeFqns.isEmpty() && !coreTypeFqns.contains(ownerFqn)) {
                // core type 목록에 없으면 공개 API 타입 인덱스로 한 번 더 확인
                String publicApiType = firstNonBlank(
                        publicApiTypeBySymbolId.get(subjectSymbolId),
                        publicApiTypeBySymbolId.get(firstNonBlank(
                                candidate.path("subjectTypeSymbolId").asText(""),
                                candidate.path("subjectTypeSymbol").asText("")
                        ))
                );
                if (publicApiType.isBlank() || !coreTypeFqns.contains(publicApiType)) {
                    continue;
                }
            }

            String methodName = extractMethodName(methodQualifiedName);
            if (methodName.isBlank()) {
                continue;
            }
            String methodFqn = toApiFqn(ownerFqn, methodName, "<init>".equals(methodName));
            String className = extractSimpleName(ownerFqn);

            JsonNode firstEvidence = firstArray(candidate, "evidences").isArray()
                    && !firstArray(candidate, "evidences").isEmpty()
                    ? firstArray(candidate, "evidences").get(0)
                    : NullNode.getInstance();

            String filePath = firstNonBlank(
                    firstEvidence.path("filePath").asText(""),
                    firstEvidence.path("file_path").asText(""),
                    firstEvidence.path("sourceFile").asText(""),
                    firstEvidence.path("source_file").asText("")
            );
            filePath = toUserFacingSourcePath(filePath);
            Integer startLine = firstNonNullInt(
                    asNullableInt(firstEvidence.path("startLine")),
                    asNullableInt(firstEvidence.path("start_line"))
            );
            Integer endLine = firstNonNullInt(
                    asNullableInt(firstEvidence.path("endLine")),
                    asNullableInt(firstEvidence.path("end_line"))
            );
            String summarySeed = firstNonBlank(
                    candidate.path("description").asText(""),
                    candidate.path("qualityReason").asText(""),
                    inferMethodUsage(methodName, className, "")
            );
            int importance = Math.max(
                    baseImportance,
                    baseImportance + (int) Math.round(Math.max(0.0d, candidate.path("score").asDouble(0.0d)) * 10.0d)
            );

            CoreMethodSeed prev = byFqn.get(methodFqn);
            CoreMethodSeed next = new CoreMethodSeed(
                    firstNonBlank(subjectSymbolId, prev == null ? "" : prev.symbolId()),
                    prev == null ? "" : prev.classSymbolId(),
                    ownerFqn,
                    className,
                    methodName,
                    methodFqn,
                    firstNonBlank(filePath, prev == null ? "" : prev.filePath()),
                    Math.max(importance, methodHeuristicBonus(methodName)),
                    prev == null ? "" : prev.signatureHint(),
                    normalizeSummarySeed(summarySeed),
                    inferScenarioHint(methodName),
                    firstNonNullInt(startLine, prev == null ? null : prev.startLine()),
                    firstNonNullInt(endLine, prev == null ? null : prev.endLine())
            );
            if (prev == null || next.importance() > prev.importance()) {
                byFqn.put(methodFqn, next);
            }
        }
    }

    /**
     * 규칙 후보를 사용자 주의사항 씨앗으로 변환한다.
     */
    /**
     * 규칙 후보를 caution seed로 변환한다.
     */
    public JsonNode buildCautionSeed(
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
                    Set<String> seenEv = new LinkedHashSet<>();
                    for (int e = 0; e < evidences.size() && evidenceIds.size() < MAX_EVIDENCE_PER_CAUTION; e++) {
                        JsonNode evidence = evidences.get(e);
                        String rawId = evidence.path("rawId").asText("");
                        if (!rawId.isBlank() && seenEv.add(rawId)) {
                            evidenceIds.add(rawId);
                        }
                    }
                }
                caution.put("confidence", round3(candidate.path("score").asDouble(0.72d)));

                JsonNode candidateSummary = candidate.path("summary");
                if (candidateSummary.isObject()) {
                    String condition = candidateSummary.path("condition").asText("");
                    String action    = candidateSummary.path("action").asText("");
                    if (!condition.isBlank()) caution.put("condition", condition);
                    if (!action.isBlank())    caution.put("action", action);
                }

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
    /**
     * core type seed를 JSON 배열로 직렬화한다.
     */
    public JsonNode buildCoreClassSeed(List<CoreTypeSeed> coreTypes) {
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
    /**
     * core method seed를 JSON 배열로 직렬화한다.
     */
    public JsonNode buildCoreMethodSeed(List<CoreMethodSeed> coreMethods) {
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
    /**
     * 메서드 사용 순서 seed를 생성한다.
     */
    public JsonNode buildMethodFlowSeed(JsonNode apiMap, List<CoreMethodSeed> coreMethods) {
        // api_map에 순서 정보가 있으면 우선 사용
        JsonNode flowFromMap = firstArray(apiMap, "methodFlow", "methodUsageOrder", "method_flow", "method_usage_order");
        if (flowFromMap.isArray() && !flowFromMap.isEmpty()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (int i = 0; i < flowFromMap.size() && out.size() < MAX_METHOD_FLOW; i++) {
                JsonNode raw = flowFromMap.get(i);
                ObjectNode step = out.addObject();
                step.put("order", raw.path("order").asInt(i + 1));
                step.put("title", shortenText(raw.path("title").asText("단계"), 50));
                step.put("description", shortenText(raw.path("description").asText("핵심 메서드를 호출한다."), 120));
                putIfText(step, "classFqn", firstNonBlank(raw.path("classFqn").asText(""), raw.path("class_fqn").asText("")));
                putIfText(step, "methodFqn", firstNonBlank(raw.path("methodFqn").asText(""), raw.path("method_fqn").asText(""), raw.path("fqn").asText("")));
                putIfText(step, "filePath", firstNonBlank(raw.path("filePath").asText(""), raw.path("file_path").asText("")));
                if (raw.path("startLine").canConvertToInt()) {
                    step.put("startLine", raw.path("startLine").asInt());
                } else if (raw.path("start_line").canConvertToInt()) {
                    step.put("startLine", raw.path("start_line").asInt());
                }
                if (raw.path("endLine").canConvertToInt()) {
                    step.put("endLine", raw.path("endLine").asInt());
                } else if (raw.path("end_line").canConvertToInt()) {
                    step.put("endLine", raw.path("end_line").asInt());
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
    /**
     * 확장 포인트 seed를 생성한다.
     */
    public JsonNode buildExtensionSeed(JsonNode apiMap, List<CoreTypeSeed> coreTypes, JsonNode subsystems) {
        ArrayNode out = objectMapper.createArrayNode();

        JsonNode extensionArray = firstArray(apiMap, "extensionPoints", "extensions", "extension_points");
        if (extensionArray.isArray()) {
            for (JsonNode item : extensionArray) {
                if (out.size() >= MAX_EXTENSION_POINTS) {
                    break;
                }
                ObjectNode node = out.addObject();
                putIfText(node, "symbolId", firstNonBlank(item.path("symbolId").asText(""), item.path("symbol_id").asText("")));
                putIfText(node, "fqn", firstNonBlank(
                        item.path("fqn").asText(""),
                        item.path("classFqn").asText(""),
                        item.path("class_fqn").asText(""),
                        item.path("qualifiedName").asText(""),
                        item.path("qualified_name").asText("")
                ));
                putIfText(node, "className", firstNonBlank(
                        item.path("className").asText(""),
                        item.path("simpleName").asText(""),
                        item.path("simple_name").asText(""),
                        extractSimpleName(node.path("fqn").asText(""))
                ));
                node.put("reason", firstNonBlank(item.path("reason").asText(""), "확장 가능 지점"));
                node.put("confidenceSource", firstNonBlank(item.path("confidenceSource").asText(""), "문서"));
                putIfText(node, "filePath", firstNonBlank(item.path("filePath").asText(""), item.path("file_path").asText("")));
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
    /**
     * 디렉터리/파일/클래스/메서드 트리 seed를 생성한다.
     */
    public JsonNode buildDirectories(JsonNode apiMap, List<CoreTypeSeed> coreTypes, List<CoreMethodSeed> coreMethods) {
        JsonNode directoriesFromMap = apiMap.path("directories");
        if (directoriesFromMap.isArray() && !directoriesFromMap.isEmpty()) {
            ArrayNode trimmed = objectMapper.createArrayNode();
            for (int i = 0; i < directoriesFromMap.size() && trimmed.size() < MAX_DIRECTORIES; i++) {
                trimmed.add(directoriesFromMap.get(i));
            }
            return trimmed;
        }

        Map<String, List<CoreTypeSeed>> typesByDir = new LinkedHashMap<>();
        Map<String, String> typeFilePathByFqn = new HashMap<>();
        for (CoreTypeSeed type : coreTypes) {
            String userFacingFilePath = toUserFacingSourcePath(type.filePath());
            if (!isUserFacingSourcePath(userFacingFilePath)) {
                continue;
            }
            String dir = extractDirectoryPath(userFacingFilePath);
            typesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(type);
            typeFilePathByFqn.put(type.fqn(), userFacingFilePath);
        }

        Map<String, List<CoreMethodSeed>> methodsByClass = new HashMap<>();
        for (CoreMethodSeed method : coreMethods) {
            methodsByClass.computeIfAbsent(method.classFqn(), k -> new ArrayList<>()).add(method);
        }
        for (List<CoreMethodSeed> methods : methodsByClass.values()) {
            methods.sort(Comparator.comparingInt(CoreMethodSeed::importance).reversed());
        }

        // core type에서 파일 경로를 찾지 못한 경우에도 core method 기준 최소 파일 트리를 만든다.
        if (typesByDir.isEmpty()) {
            return buildDirectoriesFromMethods(coreMethods);
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
                fileNode.put("path", firstNonBlank(
                        typeFilePathByFqn.get(type.fqn()),
                        toUserFacingSourcePath(type.filePath())
                ));
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
     * core type seed가 비어도 core method seed를 이용해 최소 파일 트리를 구성한다.
     */
    private JsonNode buildDirectoriesFromMethods(List<CoreMethodSeed> coreMethods) {
        Map<String, Map<String, List<CoreMethodSeed>>> methodsByDirAndClass = new LinkedHashMap<>();
        Map<String, String> classFileByFqn = new HashMap<>();
        Map<String, String> classNameByFqn = new HashMap<>();
        Map<String, String> classSymbolIdByFqn = new HashMap<>();

        for (CoreMethodSeed method : coreMethods) {
            String filePath = toUserFacingSourcePath(method.filePath());
            if (!isUserFacingSourcePath(filePath)) {
                continue;
            }
            String classFqn = sanitizeQualifiedName(firstNonBlank(
                    method.classFqn(),
                    extractOwnerFqn(method.fqn())
            ));
            if (classFqn.isBlank()) {
                continue;
            }
            String directory = extractDirectoryPath(filePath);
            methodsByDirAndClass
                    .computeIfAbsent(directory, k -> new LinkedHashMap<>())
                    .computeIfAbsent(classFqn, k -> new ArrayList<>())
                    .add(method);
            classFileByFqn.putIfAbsent(classFqn, filePath);
            classNameByFqn.putIfAbsent(classFqn, firstNonBlank(method.className(), extractSimpleName(classFqn)));
            classSymbolIdByFqn.putIfAbsent(classFqn, method.classSymbolId());
        }

        ArrayNode directories = objectMapper.createArrayNode();
        int dirCount = 0;
        for (Map.Entry<String, Map<String, List<CoreMethodSeed>>> dirEntry : methodsByDirAndClass.entrySet()) {
            if (dirCount++ >= MAX_DIRECTORIES) {
                break;
            }

            ObjectNode dirNode = directories.addObject();
            dirNode.put("path", dirEntry.getKey());
            ArrayNode files = dirNode.putArray("files");

            for (Map.Entry<String, List<CoreMethodSeed>> classEntry : dirEntry.getValue().entrySet()) {
                String classFqn = classEntry.getKey();
                List<CoreMethodSeed> classMethods = classEntry.getValue();
                classMethods.sort(Comparator.comparingInt(CoreMethodSeed::importance).reversed());

                ObjectNode fileNode = files.addObject();
                fileNode.put("path", classFileByFqn.getOrDefault(classFqn, ""));
                ArrayNode classes = fileNode.putArray("classes");
                ObjectNode classNode = classes.addObject();
                putIfText(classNode, "symbolId", classSymbolIdByFqn.getOrDefault(classFqn, ""));
                classNode.put("name", classNameByFqn.getOrDefault(classFqn, extractSimpleName(classFqn)));
                classNode.put("summary", "핵심 메서드 기반으로 추정한 클래스");
                classNode.put("estimated", true);

                ArrayNode methods = classNode.putArray("methods");
                for (int i = 0; i < classMethods.size() && i < MAX_METHODS_PER_CLASS; i++) {
                    CoreMethodSeed method = classMethods.get(i);
                    ObjectNode methodNode = methods.addObject();
                    methodNode.put("symbolId", method.symbolId());
                    methodNode.put("name", method.methodName());
                    methodNode.put("summary", method.summarySeed());
                    methodNode.put("estimated", true);
                }
            }
        }
        return directories;
    }

    /**
     * 근거 인덱스.
     */
    /**
     * 화면 표시에 사용할 근거 인덱스를 생성한다.
     */
    public JsonNode buildEvidenceIndex(
            List<CoreTypeSeed> coreTypes,
            List<CoreMethodSeed> coreMethods,
            JsonNode ruleCandidates
    ) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> dedupe = new LinkedHashSet<>();

        for (CoreTypeSeed type : coreTypes) {
            String filePath = toUserFacingSourcePath(type.filePath());
            if (!isUserFacingSourcePath(filePath)) {
                continue;
            }
            String key = "TYPE|" + type.fqn() + "|" + filePath + "|" + type.startLine() + "|" + type.endLine();
            if (!dedupe.add(key)) {
                continue;
            }
            ObjectNode node = out.addObject();
            node.put("kind", "TYPE");
            putIfText(node, "symbolId", type.symbolId());
            putIfText(node, "fqn", type.fqn());
            putIfText(node, "filePath", filePath);
            if (type.startLine() != null) {
                node.put("startLine", type.startLine());
            }
            if (type.endLine() != null) {
                node.put("endLine", type.endLine());
            }
        }

        for (CoreMethodSeed method : coreMethods) {
            String filePath = toUserFacingSourcePath(method.filePath());
            if (!isUserFacingSourcePath(filePath)) {
                continue;
            }
            String key = "METHOD|" + method.fqn() + "|" + filePath + "|" + method.startLine() + "|" + method.endLine();
            if (!dedupe.add(key)) {
                continue;
            }
            ObjectNode node = out.addObject();
            node.put("kind", "METHOD");
            putIfText(node, "symbolId", method.symbolId());
            putIfText(node, "fqn", method.fqn());
            putIfText(node, "filePath", filePath);
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
                    String rawFilePath = firstNonBlank(
                            evidence.path("filePath").asText(""),
                            evidence.path("file_path").asText(""),
                            evidence.path("sourceFile").asText(""),
                            evidence.path("source_file").asText("")
                    );
                    String filePath = toUserFacingSourcePath(rawFilePath);
                    if (!isUserFacingSourcePath(filePath)) {
                        continue;
                    }
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
                    // bytecode 경로 또는 BYTECODE 타입 evidence의 snippet은 제외한다.
                    boolean isBytecodeEvidence = isBytecodeFilePath(rawFilePath)
                            || "BYTECODE".equalsIgnoreCase(evidence.path("evidenceType").asText(""));
                    if (!isBytecodeEvidence) {
                        putIfText(node, "snippet", trimSnippet(evidence.path("snippet").asText("")));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 품질 게이트 요약.
     */
    /**
     * 입력 품질 게이트 요약을 생성한다.
     */
    public JsonNode buildQualityGate(
            JsonNode apiMap,
            JsonNode ruleCandidates,
            JsonNode rankings,
            JsonNode subsystems,
            JsonNode symbolSourceIndex
    ) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("apiMapPresent", !apiMap.isMissingNode() && !apiMap.isNull() && !apiMap.isEmpty());
        out.put("ruleCandidateCount", countArray(ruleCandidates.path("candidates")));
        out.put("rankingCount", countArray(rankings.path("symbolRankings")));
        out.put("subsystemCount", countArray(subsystems.path("subsystems")));
        out.put("symbolSourceAnchorCount", countArray(symbolSourceIndex.path("symbols")));
        out.put("inputSourcePolicy", "api_map + rule_candidates + rankings + subsystems + optional(symbol_source_index)");
        return out;
    }

    /**
     * UI 호환 API 엔트리 시드.
     */
    /**
     * UI 호환용 apiEntries를 생성한다.
     */
    public JsonNode buildApiEntries(List<CoreMethodSeed> methods) {
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
    /**
     * 요청/시드 정보를 이용해 evidence bundle을 구성한다.
     */
    public List<LlmRequest.EvidenceSnippet> resolveEvidenceBundle(LlmRequest request, JsonNode cautionSeed) {
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

    /**
     * evidence snippet 길이와 개수를 제한해 정규화한다.
     */
    public List<LlmRequest.EvidenceSnippet> trimEvidenceSnippets(List<LlmRequest.EvidenceSnippet> evidences, int limit) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<LlmRequest.EvidenceSnippet> out = new ArrayList<>();
        for (LlmRequest.EvidenceSnippet evidence : evidences) {
            if (out.size() >= limit || evidence == null) {
                break;
            }
            boolean isBytecode = "BYTECODE".equalsIgnoreCase(safeText(evidence.getEvidenceType()))
                    || isBytecodeFilePath(evidence.getFilePath());
            out.add(new LlmRequest.EvidenceSnippet(
                    evidence.getEvidenceId(),
                    safeText(evidence.getFilePath()),
                    evidence.getStartLine(),
                    evidence.getEndLine(),
                    isBytecode ? "" : safeText(trimSnippet(evidence.getSnippet())),
                    safeText(evidence.getEvidenceType())
            ));
        }
        return List.copyOf(out);
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

    /**
     * 공개 API 타입을 symbolId 기준으로 인덱싱한다.
     */
    public Map<String, String> indexPublicApiTypeBySymbolId(JsonNode apiMap, JsonNode apiSurface) {
        Map<String, String> out = new HashMap<>();

        appendPublicApiTypeIndex(out, apiMap.path("coreClasses"));
        appendPublicApiTypeIndex(out, apiMap.path("classes"));
        appendPublicApiTypeIndex(out, apiMap.path("types"));
        appendPublicApiTypeIndex(out, apiMap.path("entry_points"));
        appendPublicApiTypeIndex(out, apiMap.path("entryPoints"));
        appendPublicApiTypeIndex(out, apiMap.path("extension_points"));
        appendPublicApiTypeIndex(out, apiMap.path("extensionPoints"));

        appendPublicApiTypeIndex(out, apiSurface.path("entry_points"));
        appendPublicApiTypeIndex(out, apiSurface.path("extension_points"));

        return out;
    }

    private void appendPublicApiTypeIndex(Map<String, String> out, JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String symbolId = firstNonBlank(item.path("symbolId").asText(""), item.path("symbol_id").asText(""));
            if (symbolId.isBlank()) {
                continue;
            }
            String fqn = firstNonBlank(
                    item.path("fqn").asText(""),
                    item.path("qualifiedName").asText(""),
                    item.path("qualified_name").asText(""),
                    item.path("classFqn").asText(""),
                    item.path("class_fqn").asText("")
            );
            if (fqn.isBlank() && symbolId.startsWith("type:")) {
                fqn = symbolId;
            }
            fqn = sanitizeQualifiedName(fqn);
            if (fqn.isBlank() || isMethodQualifiedName(fqn)) {
                continue;
            }
            out.putIfAbsent(symbolId, fqn);
        }
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
}



