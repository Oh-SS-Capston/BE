package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.support.PublicSymbolFilter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 역할: EntryPointPlan.md 파이프라인 구현체.
 *
 * Phase 1 (HIGH 신호): README 언급, Javadoc @apiNote 키워드
 * Phase 2 (점수 누적): public 생성자 / static factory / builder
 * Phase 3 (점수 누적): 네이밍 컨벤션, facade 구조(public 메서드 수)
 * Phase 4 (점수 누적): 다른 public API 반환 타입 등장 빈도
 *
 * confidence: Phase 1 신호 ≥ 1 → HIGH / score ≥ 3 → MED / score ≥ 1 → LOW
 *   #9: MED 후보 중 PUBLIC_STATIC_API + 공개 의도 근거(FACADE/NAMING/README/apiguardian) 결합 시 HIGH 조건부 승격
 * role: SECONDARY (returned by public API, 직접 생성 경로 없음) / PRIMARY (그 외)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntryPointDetectService {

    private static final Set<String> EXCLUDED_PKG_SEGMENTS = Set.of(
            "internal", "impl", "util", "utils", "helper", "helpers"
    );
    // P0-1: 예제/샘플/테스트 소스 경로 배제 (ExtensionPointDetectService.EXCLUDED_PATH_MARKERS와 동형)
    private static final Set<String> EXCLUDED_PATH_MARKERS = Set.of(
            "src/test/", "src/it/", "src/integrationtest/", "src/integration-test/",
            "/example/", "/examples/", "/sample/", "/samples/", "/demo/", "/demos/"
    );
    // P0-1: com.example.* / example.* 패키지는 라이브러리가 아닌 예제 코드
    private static final Set<String> EXAMPLE_PKG_PREFIXES = Set.of(
            "com.example.", "example."
    );
    private static final Set<String> HIGH_SUFFIX = Set.of(
            "client", "builder", "template", "bootstrap", "launcher", "runner", "application"
    );
    private static final Set<String> MED_SUFFIX = Set.of(
            "factory", "manager", "facade"
    );
    private static final Set<String> FACTORY_METHOD_PREFIXES = Set.of(
            "create", "of", "from", "builder", "newbuilder", "newinstance", "getinstance"
    );
    // P1-2: apiguardian @API 어노테이션 감지용 fragment (소문자 비교)
    private static final String API_GUARDIAN_ANNOTATION_FRAGMENT = "apiguardian";
    private static final Set<String> APIGUARDIAN_STATUSES =
            Set.of("STABLE", "MAINTAINED", "EXPERIMENTAL", "INTERNAL", "DEPRECATED");
    private static final List<String> JAVADOC_KEYWORDS = List.of(
            "main entry point", "primary api", "use this class",
            "start with", "bootstrap", "configure", "@apiNote"
    );
    private static final int MIN_FACADE_METHODS = 5;
    private static final int MED_MIN_SCORE = 3;
    // #2: TYPE 진입점당 명시 진입 메서드 상한 (ApiFlowTraceService.MAX_TYPE_SEED_METHODS와 동형)
    private static final int MAX_ENTRY_METHODS = 10;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactRepository artifactRepository;
    private final EdgeInferencePolicy edgeInferencePolicy;

    public List<EntryPointCandidate> detect(String runId) {
        // 모든 심볼 한 번에 로드 → first-level cache로 lazy 연관 N+1 방지
        List<SymbolEntity> allSymbols = symbolRepository.findAllByRun_RunId(runId);
        Set<String> publicSymbolIds = loadPublicSymbolIds(allSymbols);
        if (publicSymbolIds.isEmpty()) {
            return List.of();
        }

        ChildMaps childMaps = buildChildMaps(allSymbols);
        SemanticEntrySignals semanticEntrySignals = loadSemanticEntrySignals(
                runId,
                childMaps.methodOwnerIndex()
        );

        Map<String, Integer> returnedByPublicApi =
                countReturnedByPublicApi(runId, publicSymbolIds, childMaps.methodOwnerIndex());
        Map<String, Integer> annotationUsageCounts = countAnnotationUsage(runId);

        FactsSignals factsSignals = loadFactsSignals(runId, allSymbols);
        Set<String> exportedPackages = loadExportedPackages(allSymbols);
        Set<String> implementorSymbolIds = loadSymbolsWithOutboundInheritance(runId);
        ModuleRoleIndex moduleRoleIndex = loadModuleRoleIndex(runId);

        List<EntryPointCandidate> candidates = new ArrayList<>();

        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.TYPE) continue;
            if (!publicSymbolIds.contains(symbol.getSymbolId())) continue;
            if (isExampleCandidate(symbol, moduleRoleIndex)) {
                candidates.add(exampleCandidate(symbol));
                continue;
            }
            if (shouldExclude(symbol, exportedPackages, childMaps, semanticEntrySignals)) continue;

            PhaseResult result = evaluatePhases(
                    symbol, childMaps, returnedByPublicApi, annotationUsageCounts,
                    factsSignals, implementorSymbolIds, moduleRoleIndex, semanticEntrySignals);
            if ("NONE".equals(result.confidence())) continue;

            candidates.add(EntryPointCandidate.builder()
                    .symbolId(symbol.getSymbolId())
                    .qualifiedName(symbol.getQualifiedName())
                    .ownerTypeFqn(resolveOwnerTypeFqn(symbol))
                    .simpleName(symbol.getSimpleName())
                    .typeKind(resolveTypeKind(symbol))
                    .sourceFile(resolveSourceFilePath(symbol))
                    .startLine(symbol.getSourceStartLine())
                    .endLine(symbol.getSourceEndLine())
                    .role(result.role())
                    .confidence(result.confidence())
                    .signals(List.copyOf(result.signals()))
                    .score(result.score())
                    .entryMethods(resolveEntryMethods(symbol.getSymbolId(), childMaps, semanticEntrySignals))
                    .evidenceCompleteness(result.evidenceCompleteness())
                    .build());
        }

        candidates.sort(Comparator
                .comparingInt((EntryPointCandidate c) -> confidenceOrder(c.getConfidence()))
                .thenComparingInt(c -> "EXAMPLE".equals(c.getRole()) ? 1 : 0)
                .thenComparingInt(c -> -c.getScore()));

        return List.copyOf(candidates);
    }

    // ─── 데이터 로딩 ────────────────────────────────────────────────────────────

    private Set<String> loadPublicSymbolIds(List<SymbolEntity> allSymbols) {
        // allSymbols는 detect() 상단에서 이미 로드됨 — 추가 DB 쿼리 없음
        // 판정 기준은 PublicSymbolFilter에서 단일 관리 (sync와 parity 보장)
        return allSymbols.stream()
                .filter(PublicSymbolFilter::isPublicApiType)
                .map(SymbolEntity::getSymbolId)
                .collect(Collectors.toSet());
    }

    private ChildMaps buildChildMaps(List<SymbolEntity> allSymbols) {
        Map<String, List<SymbolEntity>> constructorsByOwner = new HashMap<>();
        Map<String, List<SymbolEntity>> methodsByOwner      = new HashMap<>();
        Map<String, String>            methodOwnerIndex     = new HashMap<>();

        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getOwner() == null) continue;
            String ownerSymbolId = symbol.getOwner().getSymbolId();

            if (symbol.getSymbolKind() == SymbolKind.CONSTRUCTOR) {
                constructorsByOwner
                        .computeIfAbsent(ownerSymbolId, k -> new ArrayList<>())
                        .add(symbol);
            } else if (symbol.getSymbolKind() == SymbolKind.METHOD) {
                methodsByOwner
                        .computeIfAbsent(ownerSymbolId, k -> new ArrayList<>())
                        .add(symbol);
                methodOwnerIndex.put(symbol.getSymbolId(), ownerSymbolId);
            }
        }
        return new ChildMaps(constructorsByOwner, methodsByOwner, methodOwnerIndex);
    }

    /**
     * Extraction resolver가 만든 HANDLES_ENDPOINT 관계를 API 진입점 판정용 신호로 변환한다.
     *
     * <p>기존에는 Controller가 public 생성자/factory가 없다는 이유로 제외될 수 있었고,
     * 실제 endpoint method가 API flow seed에 들어가지 못했다. 이제 resolved/confidence 정책을
     * 통과한 endpoint edge는 타입 진입점과 seed method를 직접 보강한다.</p>
     */
    private SemanticEntrySignals loadSemanticEntrySignals(
            String runId,
            Map<String, String> methodOwnerIndex
    ) {
        List<Edge> endpointEdges = edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(
                runId,
                List.of(EdgeType.HANDLES_ENDPOINT)
        );

        Set<String> usableOwnerIds = new HashSet<>();
        Set<String> highTrustOwnerIds = new HashSet<>();
        Map<String, List<String>> endpointMethodIdsByOwner = new HashMap<>();
        Map<String, List<EntryPointCandidate.HttpEndpointInfo>> endpointInfosByMethodId = new HashMap<>();

        for (Edge edge : endpointEdges) {
            if (!edgeInferencePolicy.isUsableForInference(edge) || edge.getFromSymbol() == null) {
                continue;
            }

            SymbolEntity from = edge.getFromSymbol();
            String fromId = from.getSymbolId();
            if (fromId == null) {
                continue;
            }

            String ownerId;
            if (from.getSymbolKind() == SymbolKind.TYPE) {
                ownerId = fromId;
            } else {
                ownerId = methodOwnerIndex.get(fromId);
            }
            if (ownerId == null) {
                continue;
            }

            usableOwnerIds.add(ownerId);
            if (edgeInferencePolicy.isHighTrust(edge)) {
                highTrustOwnerIds.add(ownerId);
            }
            if (from.getSymbolKind() == SymbolKind.METHOD) {
                endpointMethodIdsByOwner
                        .computeIfAbsent(ownerId, ignored -> new ArrayList<>())
                        .add(fromId);
                endpointInfosByMethodId
                        .computeIfAbsent(fromId, ignored -> new ArrayList<>())
                        .add(toHttpEndpointInfo(edge));
            }
        }

        Map<String, List<String>> immutableMethods = new HashMap<>();
        endpointMethodIdsByOwner.forEach((ownerId, methodIds) ->
                immutableMethods.put(ownerId, methodIds.stream().distinct().sorted().toList()));

        Map<String, List<EntryPointCandidate.HttpEndpointInfo>> immutableInfos = new HashMap<>();
        endpointInfosByMethodId.forEach((methodId, infos) ->
                immutableInfos.put(methodId, List.copyOf(infos)));

        return new SemanticEntrySignals(
                Set.copyOf(usableOwnerIds),
                Set.copyOf(highTrustOwnerIds),
                Map.copyOf(immutableMethods),
                Map.copyOf(immutableInfos)
        );
    }

    private EntryPointCandidate.HttpEndpointInfo toHttpEndpointInfo(Edge edge) {
        JsonNode attrs = edge.getAttrs();
        String httpMethod = attrs == null ? null : attrs.path("http_method").asText(null);
        String path = attrs == null ? null : attrs.path("path").asText(null);
        return EntryPointCandidate.HttpEndpointInfo.builder()
                .httpMethod(httpMethod)
                .path(path)
                .confidence(edge.getConfidence() == null ? null : edge.getConfidence().doubleValue())
                .resolution(edge.getResolution() == null ? null : edge.getResolution().name())
                .resolutionReason(edge.getResolutionReason())
                .origin(edge.getOrigin() == null ? null : edge.getOrigin().name())
                .derivationKind(edge.getDerivationKind() == null ? null : edge.getDerivationKind().name())
                .defaultVisible(edgeInferencePolicy.defaultVisible(edge))
                .build();
    }

    /**
     * #5: ANNOTATED_WITH 엣지의 인바운드 카운트 — 어노테이션 타입별 사용 빈도.
     * RETURNS 빈도 집계(countReturnedByPublicApi)와 동형 구조.
     */
    private Map<String, Integer> countAnnotationUsage(String runId) {
        List<Edge> edges = edgeRepository
                .findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.ANNOTATED_WITH);
        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : edges) {
            if (!edgeInferencePolicy.isUsableForInference(edge) || edge.getToSymbol() == null) continue;
            String annotationId = edge.getToSymbol().getSymbolId();
            counts.merge(annotationId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * RETURNS 엣지에서 public API 메서드가 반환하는 타입별 빈도를 계산한다.
     * → 빈도가 높은 타입은 Secondary entry point 후보.
     */
    private Map<String, Integer> countReturnedByPublicApi(
            String runId,
            Set<String> publicSymbolIds,
            Map<String, String> methodOwnerIndex) {

        List<Edge> returnsEdges = edgeRepository
                .findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.RETURNS);

        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : returnsEdges) {
            if (!edgeInferencePolicy.isUsableForInference(edge) || edge.getToSymbol() == null) continue;
            String methodId   = edge.getFromSymbol().getSymbolId();
            String ownerSymId = methodOwnerIndex.get(methodId);
            if (ownerSymId == null || !publicSymbolIds.contains(ownerSymId)) continue;

            String returnedTypeId = edge.getToSymbol().getSymbolId();
            counts.merge(returnedTypeId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * FACTS_JSON 아티팩트에서 README 언급 / 예제 코드 참조 신호를 추출한다.
     *
     * README 매칭 전략(#3): FQN 우선 — target_symbol에 패키지가 포함된 경우 FQN 버킷에 보관하고
     * 후보 qualifiedName(prefix 정규화 후)과 정확 일치 시에만 README_MENTION HIGH 승격.
     * FQN 매칭 실패 시 simpleName fallback은 프로젝트 내 해당 simpleName 타입이 유일할 때만 허용.
     */
    private FactsSignals loadFactsSignals(String runId, List<SymbolEntity> allSymbols) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON);

        // 프로젝트 TYPE 심볼의 simpleName 분포 — simpleName fallback 모호성 판정에 사용
        Map<String, Long> projectSimpleNameCount = allSymbols.stream()
                .filter(s -> s.getSymbolKind() == SymbolKind.TYPE
                        && s.getSimpleName() != null && !s.getSimpleName().isEmpty())
                .collect(Collectors.groupingBy(SymbolEntity::getSimpleName, Collectors.counting()));

        if (opt.isEmpty()) {
            return new FactsSignals(Set.of(), Set.of(), Map.copyOf(projectSimpleNameCount), Set.of());
        }

        JsonNode meta = opt.get().getMeta();
        Set<String> readmeMentionedFqns        = new HashSet<>();
        Set<String> readmeMentionedSimpleNames  = new HashSet<>();
        Set<String> exampleReferenced          = new HashSet<>();

        // observations.readme_mentions 버킷: target_symbol = simpleName 또는 FQCN
        JsonNode readmeMentions = meta.path("observations").path("readme_mentions");
        if (readmeMentions.isArray()) {
            for (JsonNode obs : readmeMentions) {
                String symbol = obs.path("target_symbol").asText("");
                int colonIdx = symbol.indexOf(':');
                String fqn = colonIdx >= 0 ? symbol.substring(colonIdx + 1) : symbol;
                if (fqn.contains(".")) {
                    readmeMentionedFqns.add(fqn);
                }
                String simpleName = extractSimpleName(symbol);
                if (!simpleName.isEmpty()) readmeMentionedSimpleNames.add(simpleName);
            }
        }

        JsonNode evidenceArray = meta.path("evidence");
        if (evidenceArray.isArray()) {
            for (JsonNode ev : evidenceArray) {
                String filePath = ev.path("path").asText("")
                        .replace('\\', '/').toLowerCase(Locale.ROOT);
                if (filePath.contains("/example") || filePath.contains("/sample")
                        || filePath.contains("/demo")) {
                    String symbol = ev.path("symbol").asText("");
                    String simpleName = extractSimpleName(symbol);
                    if (!simpleName.isEmpty()) exampleReferenced.add(simpleName);
                }
            }
        }

        return new FactsSignals(
                Set.copyOf(readmeMentionedFqns),
                Set.copyOf(readmeMentionedSimpleNames),
                Map.copyOf(projectSimpleNameCount),
                Set.copyOf(exampleReferenced));
    }

    /**
     * JPMS를 사용하는 프로젝트에서 module-info.java의 exports 패키지만 반환한다.
     * MODULE 심볼이 없으면 빈 Set을 반환해 범위 필터를 건너뛴다.
     */
    private Set<String> loadExportedPackages(List<SymbolEntity> allSymbols) {
        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.MODULE) continue;
            JsonNode sig     = symbol.getSignature();
            JsonNode exports = sig == null ? null : sig.path("exports");
            if (exports == null || !exports.isArray() || exports.isEmpty()) continue;

            Set<String> exported = new HashSet<>();
            for (JsonNode pkg : exports) {
                exported.add(pkg.asText());
            }
            return Set.copyOf(exported);
        }
        return Set.of();
    }

    private Set<String> loadSymbolsWithOutboundInheritance(String runId) {
        List<Edge> edges = edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(
                runId, List.of(EdgeType.IMPLEMENTS, EdgeType.EXTENDS));
        Set<String> result = new HashSet<>(edges.size());
        for (Edge e : edges) {
            if (!edgeInferencePolicy.isUsableForInference(e)) continue;
            if (e.getFromSymbol() != null) {
                result.add(e.getFromSymbol().getSymbolId());
            }
        }
        return Set.copyOf(result);
    }

    private ModuleRoleIndex loadModuleRoleIndex(String runId) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST);
        if (opt.isEmpty()) {
            return ModuleRoleIndex.empty();
        }

        Map<String, ModuleRole> bySourceRoot = new HashMap<>();
        JsonNode modules = opt.get().getMeta().path("modules");
        if (!modules.isArray()) {
            return ModuleRoleIndex.empty();
        }

        for (JsonNode module : modules) {
            String moduleId = module.path("moduleId").asText("");
            String name = module.path("name").asText("");
            String artifactId = module.path("artifactId").asText("");
            String groupId = module.path("groupId").asText("");
            String roleText = (moduleId + " " + name + " " + artifactId).toLowerCase(Locale.ROOT);
            boolean exampleModule = containsAny(roleText,
                    "example", "examples", "sample", "samples", "demo", "demos", "documentation", "docs", "test", "tests");
            boolean publishedLibrary = !exampleModule && !groupId.isBlank() && !artifactId.isBlank();
            String buildModeStr = module.path("buildMode").asText("UNKNOWN");
            ModuleRole role = new ModuleRole(exampleModule, publishedLibrary, buildModeStr);
            registerModuleRoots(bySourceRoot, module.path("sourceRoots"), role);
            registerModuleRoots(bySourceRoot, module.path("testRoots"), new ModuleRole(true, false, buildModeStr));
        }
        return new ModuleRoleIndex(Map.copyOf(bySourceRoot));
    }

    // ─── 필터 ────────────────────────────────────────────────────────────────

    /**
     * 제외 조건 하나라도 해당하면 true.
     *
     * 1. 예제/샘플/테스트 소스 경로 (P0-1)
     * 2. com.example.* / example.* 패키지 (P0-1)
     * 3. internal / impl / util / helper 패키지 세그먼트
     * 4. JPMS 범위 필터 (module-info.java exports 미포함 패키지)
     * 5. @Deprecated
     * 6. abstract class (static factory/facade가 없는 경우)
     * 7. public 생성자도 없고 static factory도 없고 static facade도 아닌 class / record
     */
    private boolean shouldExclude(SymbolEntity symbol,
                                   Set<String> exportedPackages,
                                   ChildMaps childMaps,
                                   SemanticEntrySignals semanticEntrySignals) {
        String qualifiedName = symbol.getQualifiedName();

        // P1-2: @API(INTERNAL/DEPRECATED) → 공개 API 후보 제외
        String apiGuardianStatus = detectApiGuardianStatus(symbol);
        if ("INTERNAL".equals(apiGuardianStatus) || "DEPRECATED".equals(apiGuardianStatus)) return true;

        // P0-1: 예제/샘플/테스트 소스 경로 배제
        if (hasExcludedSourcePath(symbol)) return true;
        // P0-1: com.example.* / example.* 패키지 배제
        if (hasExamplePackage(qualifiedName)) return true;

        if (hasExcludedPackageSegment(qualifiedName)) return true;
        if (isDeprecated(symbol)) return true;

        // usable HTTP endpoint는 Java 생성 경로가 없어도 외부 진입점이다.
        // 명시적 deprecated/internal/test 필터를 통과한 뒤 JPMS export / 생성자·factory 휴리스틱만 우회한다.
        if (semanticEntrySignals.hasUsableEndpoint(symbol.getSymbolId())) {
            return false;
        }

        if (!exportedPackages.isEmpty()) {
            String pkg = extractPackageName(qualifiedName);
            if (!exportedPackages.contains(pkg)) return true;
        }

        String typeKind = resolveTypeKind(symbol);

        if ("class".equals(typeKind) && hasAbstractModifier(symbol.getModifiers())) {
            List<SymbolEntity> methods =
                    childMaps.methodsByOwner().getOrDefault(symbol.getSymbolId(), List.of());
            boolean hasStaticFactory = methods.stream()
                    .anyMatch(m -> m.getAccess() == AccessLevel.PUBLIC
                            && isStaticMethod(m)
                            && isFactoryMethodName(m.getSimpleName()));
            // P0-5: 정적 facade도 허용 (abstract + static factory 없어도 static 메서드가 다수면 유지)
            if (!hasStaticFactory && !isStaticFacadeType(methods)) return true;
        }

        if (("class".equals(typeKind) || "record".equals(typeKind))) {
            List<SymbolEntity> constructors =
                    childMaps.constructorsByOwner().getOrDefault(symbol.getSymbolId(), List.of());
            List<SymbolEntity> methods =
                    childMaps.methodsByOwner().getOrDefault(symbol.getSymbolId(), List.of());
            // P0-5: public static 메서드가 다수인 정적 facade(Mockito 등)는 생성자/factory 없어도 허용
            if (!hasInstantiationPath(constructors, methods) && !isStaticFacadeType(methods)) return true;
        }

        return false;
    }

    // ─── 페이즈 평가 ──────────────────────────────────────────────────────────

    private PhaseResult evaluatePhases(SymbolEntity symbol,
                                        ChildMaps childMaps,
                                        Map<String, Integer> returnedByPublicApi,
                                        Map<String, Integer> annotationUsageCounts,
                                        FactsSignals factsSignals,
                                        Set<String> implementorSymbolIds,
                                        ModuleRoleIndex moduleRoleIndex,
                                        SemanticEntrySignals semanticEntrySignals) {
        // #6: 심볼의 근거 추출 완전성을 먼저 계산 (모든 반환 경로에서 사용)
        EntryPointCandidate.EvidenceCompleteness ec = buildEvidenceCompleteness(symbol, moduleRoleIndex);

        List<String> signals  = new ArrayList<>();
        String simpleName     = symbol.getSimpleName();
        String symbolId       = symbol.getSymbolId();
        boolean highTrustEndpoint = semanticEntrySignals.hasHighTrustEndpoint(symbolId);
        boolean usableEndpoint = semanticEntrySignals.hasUsableEndpoint(symbolId);

        if (highTrustEndpoint) {
            signals.add("HANDLES_ENDPOINT_RESOLVED");
        }

        // Phase 1 — 문서 직접 언급 신호 (HIGH 즉시 확정)
        // P0-1: EXAMPLE_CODE_REFERENCE는 HIGH 승격 신호에서 제거. 예제 경로 심볼은 shouldExclude()에서 차단.
        // #3: FQN 우선 매칭. simpleName fallback은 프로젝트 내 해당 simpleName 타입이 유일할 때만 허용.
        String qualifiedName = symbol.getQualifiedName();
        if (qualifiedName != null) {
            int cIdx = qualifiedName.indexOf(':');
            String fqn = cIdx >= 0 ? qualifiedName.substring(cIdx + 1) : qualifiedName;
            boolean readmeMatch = factsSignals.readmeMentionedFqns().contains(fqn)
                    || (simpleName != null
                        && factsSignals.readmeMentionedSimpleNames().contains(simpleName)
                        && factsSignals.projectSimpleNameCount().getOrDefault(simpleName, 0L) == 1L);
            if (readmeMatch) signals.add("README_MENTION");
        }
        if (hasJavadocEntryPointSignal(symbol)) {
            signals.add("JAVADOC_ENTRY_POINT");
        }
        // P1-2: @API(STABLE/MAINTAINED) → Phase 1 HIGH 신호 (shouldExclude에서 INTERNAL/DEPRECATED 이미 차단됨)
        String apiGuardianStatus = detectApiGuardianStatus(symbol);
        if ("STABLE".equals(apiGuardianStatus) || "MAINTAINED".equals(apiGuardianStatus)) {
            signals.add("API_GUARDIAN_STABLE");
        }

        if (!signals.isEmpty()) {
            int bonus = phase2Score(symbolId, childMaps, signals)
                    + phase3Score(symbol, childMaps, signals);
            if (moduleRoleIndex.isPublishedLibrary(symbol)) {
                signals.add("PUBLISHED_LIBRARY_MODULE");
                bonus += 1;
            }
            String role = determineRole(symbolId, signals, returnedByPublicApi);
            // #5: 어노테이션 타입은 Phase 1 HIGH 경로에서도 PRIMARY 강제
            if ("annotation".equals(resolveTypeKind(symbol))) role = "PRIMARY";
            // #6: javadoc·annotation 모두 미추출 → HIGH 승격 보류, MED 캡 + EVIDENCE_DEGRADED 신호
            if (!highTrustEndpoint
                    && !ec.isJavadocAvailable()
                    && !ec.isAnnotationsAvailable()) {
                signals.add("EVIDENCE_DEGRADED");
                ec = ec.toBuilder().degraded(true).build();
                return new PhaseResult("MED", role, signals, 10 + bonus, ec);
            }
            return new PhaseResult("HIGH", role, signals, 10 + bonus, ec);
        }

        // Phase 2 ~ 4 — 점수 누적
        int score = phase2Score(symbolId, childMaps, signals)
                + phase3Score(symbol, childMaps, signals)
                + phase4Score(symbolId, returnedByPublicApi, signals);

        if (usableEndpoint && !highTrustEndpoint) {
            signals.add("HANDLES_ENDPOINT_INFERRED");
            score += MED_MIN_SCORE;
        }

        // #5: 어노테이션 타입 전용 신호 (constructor/factory 신호 부재 보완)
        score += phaseAnnotationScore(symbol, annotationUsageCounts, signals);

        // P1-2: @API(EXPERIMENTAL) → MED_MIN_SCORE 이상 보장 (애너테이션/인터페이스 타입 포함)
        if ("EXPERIMENTAL".equals(apiGuardianStatus)) {
            signals.add("API_GUARDIAN_EXPERIMENTAL");
            score = Math.max(score, MED_MIN_SCORE);
        }
        if (score > 0 && moduleRoleIndex.isPublishedLibrary(symbol)) {
            signals.add("PUBLISHED_LIBRARY_MODULE");
            score += 1;
        }

        // #4: DI 스테레오타입 빈 오탐 억제
        BeanKind beanKind = detectBeanKind(symbol);
        if (beanKind == BeanKind.STEREOTYPE) {
            signals.add("STEREOTYPE_BEAN");
            // PUBLIC_CONSTRUCTOR 무력화: DI 컨테이너가 생성 → 사용자 직접 생성의 증거 아님
            if (signals.contains("PUBLIC_CONSTRUCTOR")) {
                score -= 2;
            }
            // 라이브러리 진입 신호(STATIC_FACTORY·NAMING_HIGH·PUBLISHED_LIBRARY_MODULE)가 없으면 NONE
            boolean hasSurvivorSignal = signals.contains("STATIC_FACTORY")
                    || signals.contains("NAMING_HIGH")
                    || signals.contains("PUBLISHED_LIBRARY_MODULE")
                    || signals.contains("HANDLES_ENDPOINT_INFERRED");
            if (!hasSurvivorSignal) {
                return new PhaseResult("NONE", null, signals, 0, ec);
            }
        } else if (beanKind == BeanKind.CONFIGURATION) {
            // @AutoConfiguration 없는 내부 설정성 @Configuration → 감점 (강등 금지 신호는 Phase 1 HIGH에서 이미 처리)
            signals.add("INTERNAL_CONFIG");
            score = Math.max(0, score - 1);
        }

        if (score == 0) return new PhaseResult("NONE", null, signals, 0, ec);

        String confidence = score >= MED_MIN_SCORE ? "MED" : "LOW";

        // 내부 구현체 제외:
        // PUBLIC_CONSTRUCTOR 신호만 있고(LOW 확정) + IMPLEMENTS/EXTENDS 아웃바운드 엣지 존재
        // → 사용자가 직접 생성하는 타입이 아니라 인터페이스 내부 구현체로 판단
        if ("LOW".equals(confidence)
                && signals.size() == 1 && signals.contains("PUBLIC_CONSTRUCTOR")
                && implementorSymbolIds.contains(symbolId)) {
            return new PhaseResult("NONE", null, signals, 0, ec);
        }

        // #9: 정적 facade(PUBLIC_STATIC_API) 조건부 HIGH 승격 (단독 승격 금지)
        confidence = maybePromoteStaticFacade(confidence, signals, ec);

        String role = determineRole(symbolId, signals, returnedByPublicApi);
        // #5: 어노테이션 타입은 직접 사용 진입면 → PRIMARY 강제
        if ("annotation".equals(resolveTypeKind(symbol))) role = "PRIMARY";
        return new PhaseResult(confidence, role, signals, score, ec);
    }

    /**
     * #9: 정적 facade(PUBLIC_STATIC_API)의 조건부 HIGH 승격.
     *
     * PUBLIC_STATIC_API는 주(主) 근거가 아니라 보조 신호다 — 단독으로 HIGH를 만들지 않는다
     * (정적 메서드만 많은 단순 유틸 클래스를 HIGH로 오탐할 위험). 이미 MED 이상의 근거를 가진
     * 후보 중 공개 의도 추가 근거가 결합될 때만 HIGH로 보정한다.
     *
     * 승격 조건(AND):
     *   ① 현재 MED 이상 (score ≥ MED_MIN_SCORE → confidence == "MED")
     *   ② 공개 의도 추가 근거 1개 이상:
     *        README_MENTION | API_GUARDIAN_STABLE | FACADE_STRUCTURE | NAMING_HIGH | NAMING_MED
     *      (README_MENTION·API_GUARDIAN_STABLE는 발생 시 Phase 1 HIGH로 선분기되므로
     *       점수 경로의 실효 근거는 FACADE_STRUCTURE·NAMING이다. 정책 일관성을 위해 함께 명시.)
     *   ③ internal/impl/util/helper/test 패키지 배제 — shouldExclude()에서 이미 보장.
     *   ④ RETURNED_BY_PUBLIC_API 단독 근거가 아님 — ②의 화이트리스트에 미포함이라 자동 보장.
     *   ⑤ evidence_degraded 아님 — javadoc·annotation 둘 다 부재면 승격 보류(#6 MED 캡 유지).
     *
     * 승격 시 PUBLIC_STATIC_API_PROMOTED 신호를 부여해 산출물에서 승격 사유를 추적 가능하게 한다.
     */
    private String maybePromoteStaticFacade(String confidence,
                                            List<String> signals,
                                            EntryPointCandidate.EvidenceCompleteness ec) {
        if (!"MED".equals(confidence)) return confidence;                 // ①
        if (!signals.contains("PUBLIC_STATIC_API")) return confidence;
        if (!ec.isJavadocAvailable() && !ec.isAnnotationsAvailable()) {   // ⑤
            return confidence;
        }
        boolean hasPublicIntent = signals.contains("README_MENTION")
                || signals.contains("API_GUARDIAN_STABLE")
                || signals.contains("FACADE_STRUCTURE")
                || signals.contains("NAMING_HIGH")
                || signals.contains("NAMING_MED");                        // ②④
        if (!hasPublicIntent) return confidence;

        signals.add("PUBLIC_STATIC_API_PROMOTED");
        return "HIGH";
    }

    /**
     * Phase 2: public 생성자 / static factory / builder
     * 각각 +2점.
     */
    private int phase2Score(String symbolId, ChildMaps childMaps, List<String> signals) {
        int score = 0;
        List<SymbolEntity> constructors =
                childMaps.constructorsByOwner().getOrDefault(symbolId, List.of());
        List<SymbolEntity> methods =
                childMaps.methodsByOwner().getOrDefault(symbolId, List.of());

        boolean hasPublicConstructor = constructors.stream()
                .anyMatch(c -> c.getAccess() == AccessLevel.PUBLIC);
        if (hasPublicConstructor) {
            signals.add("PUBLIC_CONSTRUCTOR");
            score += 2;
        }

        boolean hasStaticFactory = methods.stream()
                .anyMatch(m -> m.getAccess() == AccessLevel.PUBLIC
                        && isStaticMethod(m)
                        && isFactoryMethodName(m.getSimpleName()));
        if (hasStaticFactory) {
            signals.add("STATIC_FACTORY");
            score += 2;
        }

        return score;
    }

    /**
     * Phase 3: 네이밍 컨벤션 + facade 구조(public 메서드 수)
     * HIGH 이름 접미사 +2, MED +1, facade(public 메서드 ≥ 5) +1, 정적 facade(public static ≥ 5) +2.
     */
    private int phase3Score(SymbolEntity symbol, ChildMaps childMaps, List<String> signals) {
        int score  = 0;
        String name = symbol.getSimpleName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HIGH_SUFFIX.stream().anyMatch(lower::endsWith)) {
                signals.add("NAMING_HIGH");
                score += 2;
            } else if (MED_SUFFIX.stream().anyMatch(lower::endsWith)) {
                signals.add("NAMING_MED");
                score += 1;
            }
        }

        List<SymbolEntity> ownedMethods = childMaps.methodsByOwner()
                .getOrDefault(symbol.getSymbolId(), List.of());

        long publicMethodCount = ownedMethods.stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC)
                .count();
        if (publicMethodCount >= MIN_FACADE_METHODS) {
            signals.add("FACADE_STRUCTURE");
            score += 1;
        }

        // P0-5: 정적 facade 신호 — public static 메서드가 다수인 타입(Mockito.mock/spy 등)
        long publicStaticCount = ownedMethods.stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC && isStaticMethod(m))
                .count();
        if (publicStaticCount >= MIN_FACADE_METHODS) {
            signals.add("PUBLIC_STATIC_API");
            score += 2;
        }

        return score;
    }

    /**
     * Phase 4: 다른 public API 메서드의 반환 타입으로 등장
     * 1회 이상이면 +1점.
     */
    private int phase4Score(String symbolId,
                              Map<String, Integer> returnedByPublicApi,
                              List<String> signals) {
        if (returnedByPublicApi.getOrDefault(symbolId, 0) >= 1) {
            signals.add("RETURNED_BY_PUBLIC_API");
            return 1;
        }
        return 0;
    }

    /**
     * Secondary 조건: 다른 public API가 반환하고, 강한 직접 진입 신호가 없는 경우.
     *
     * PUBLIC_CONSTRUCTOR만으로는 PRIMARY 확정 불가.
     * "사용자가 여기서 시작한다"는 의도 신호(README·Javadoc·네이밍·facade·static factory)가
     * 있어야 RETURNED_BY_PUBLIC_API를 이겨 PRIMARY 유지.
     */
    private String determineRole(String symbolId,
                                  List<String> signals,
                                  Map<String, Integer> returnedByPublicApi) {
        boolean returnedByApi = returnedByPublicApi.getOrDefault(symbolId, 0) >= 1;
        if (!returnedByApi) return "PRIMARY";

        boolean hasStrongEntrySignal = signals.contains("README_MENTION")
                || signals.contains("JAVADOC_ENTRY_POINT")
                || signals.contains("NAMING_HIGH")
                || signals.contains("FACADE_STRUCTURE")
                || signals.contains("STATIC_FACTORY")
                || signals.contains("HANDLES_ENDPOINT_RESOLVED")
                || signals.contains("HANDLES_ENDPOINT_INFERRED");
        return hasStrongEntrySignal ? "PRIMARY" : "SECONDARY";
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────────

    // P0-1: 소스 경로 기반 예제 배제 (ExtensionPointDetectService와 동형)
    private boolean hasExcludedSourcePath(SymbolEntity symbol) {
        if (symbol.getSourceFile() == null) return false;
        String path = symbol.getSourceFile().getPath();
        if (path == null) return false;
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return EXCLUDED_PATH_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean isExampleCandidate(SymbolEntity symbol, ModuleRoleIndex moduleRoleIndex) {
        return hasExampleSourcePath(symbol)
                || hasExamplePackage(symbol.getQualifiedName())
                || moduleRoleIndex.isExampleModule(symbol);
    }

    private boolean hasExampleSourcePath(SymbolEntity symbol) {
        if (symbol.getSourceFile() == null || symbol.getSourceFile().getPath() == null) return false;
        String normalized = symbol.getSourceFile().getPath().replace('\\', '/').toLowerCase(Locale.ROOT);
        return containsAny(normalized, "/example/", "/examples/", "/sample/", "/samples/", "/demo/", "/demos/");
    }

    private EntryPointCandidate exampleCandidate(SymbolEntity symbol) {
        return EntryPointCandidate.builder()
                .symbolId(symbol.getSymbolId())
                .qualifiedName(symbol.getQualifiedName())
                .ownerTypeFqn(resolveOwnerTypeFqn(symbol))
                .simpleName(symbol.getSimpleName())
                .typeKind(resolveTypeKind(symbol))
                .sourceFile(resolveSourceFilePath(symbol))
                .startLine(symbol.getSourceStartLine())
                .endLine(symbol.getSourceEndLine())
                .role("EXAMPLE")
                .confidence("LOW")
                .signals(List.of("EXAMPLE_CODE_REFERENCE"))
                .score(0)
                .build();
    }

    // P0-1: com.example.* / example.* 패키지는 예제 코드이므로 Public API 후보에서 제외
    private boolean hasExamplePackage(String qualifiedName) {
        if (qualifiedName == null) return false;
        int colonIdx = qualifiedName.indexOf(':');
        String fqn = colonIdx >= 0 ? qualifiedName.substring(colonIdx + 1) : qualifiedName;
        String lower = fqn.toLowerCase(Locale.ROOT);
        return EXAMPLE_PKG_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    // P0-5: public static 메서드가 다수인 정적 facade(Mockito 등) — 생성자/factory 없어도 entry 후보로 허용
    private boolean isStaticFacadeType(List<SymbolEntity> methods) {
        long publicStaticCount = methods.stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC && isStaticMethod(m))
                .count();
        return publicStaticCount >= MIN_FACADE_METHODS;
    }

    private boolean hasExcludedPackageSegment(String qualifiedName) {
        if (qualifiedName == null) return false;
        String pkg = extractPackageName(qualifiedName);
        if (pkg == null) return false;
        for (String segment : pkg.split("\\.")) {
            if (EXCLUDED_PKG_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String extractPackageName(String qualifiedName) {
        if (qualifiedName == null) return null;
        // kind prefix ("type:", "method:", …) 제거 후 패키지 추출
        int colonIdx = qualifiedName.indexOf(':');
        String fqn = colonIdx >= 0 ? qualifiedName.substring(colonIdx + 1) : qualifiedName;
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(0, idx) : null;
    }

    private boolean isDeprecated(SymbolEntity symbol) {
        // annotations는 signature가 아니라 SymbolEntity 전용 컬럼에 저장된다.
        // 노드는 객체 형태이므로 이름은 raw 키 기반으로 추출한다(extractAnnotationNameRaw).
        JsonNode annotations = symbol.getAnnotations();
        if (annotations != null && annotations.isArray()) {
            for (JsonNode ann : annotations) {
                String name = extractAnnotationNameRaw(ann).toLowerCase(Locale.ROOT);
                if (name.contains("deprecated")) return true;
            }
        }
        JsonNode modifiers = symbol.getModifiers();
        if (modifiers != null && modifiers.isArray()) {
            for (JsonNode mod : modifiers) {
                if ("deprecated".equalsIgnoreCase(mod.asText())) return true;
            }
        }
        return false;
    }

    private boolean hasAbstractModifier(JsonNode modifiers) {
        if (modifiers == null || !modifiers.isArray()) return false;
        for (JsonNode mod : modifiers) {
            if ("abstract".equalsIgnoreCase(mod.asText())) return true;
        }
        return false;
    }

    private String resolveTypeKind(SymbolEntity symbol) {
        // typeKind는 signature가 아니라 SymbolEntity 전용 컬럼에 저장된다(FactsSymbolConverter).
        String typeKind = symbol.getTypeKind();
        if (typeKind != null && !typeKind.isBlank()) return typeKind.toLowerCase(Locale.ROOT);
        return "class";
    }

    private boolean hasInstantiationPath(List<SymbolEntity> constructors,
                                          List<SymbolEntity> methods) {
        if (constructors.stream().anyMatch(c -> c.getAccess() == AccessLevel.PUBLIC)) return true;
        return methods.stream().anyMatch(m ->
                m.getAccess() == AccessLevel.PUBLIC
                        && isStaticMethod(m)
                        && isFactoryMethodName(m.getSimpleName()));
    }

    private boolean isStaticMethod(SymbolEntity method) {
        JsonNode modifiers = method.getModifiers();
        if (modifiers == null || !modifiers.isArray()) return false;
        for (JsonNode mod : modifiers) {
            if ("static".equalsIgnoreCase(mod.asText())) return true;
        }
        return false;
    }

    private boolean isFactoryMethodName(String simpleName) {
        if (simpleName == null) return false;
        String lower = simpleName.toLowerCase(Locale.ROOT);
        return FACTORY_METHOD_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Javadoc 본문에 entry point 키워드가 포함되어 있으면 true.
     * signature.javadoc 필드에서 읽는다 (facts.json 완료 가정).
     */
    private boolean hasJavadocEntryPointSignal(SymbolEntity symbol) {
        JsonNode sig = symbol.getSignature();
        if (sig == null || sig.isNull()) return false;
        String javadoc = sig.path("javadoc").asText("").toLowerCase(Locale.ROOT);
        if (javadoc.isBlank()) return false;
        return JAVADOC_KEYWORDS.stream().anyMatch(javadoc::contains);
    }

    /**
     * api_map / api_surface의 owner_type_fqn 앵커를 결정한다.
     * - TYPE 단위 후보는 자기 자신의 FQN이 owner 역할을 수행한다.
     */
    private String resolveOwnerTypeFqn(SymbolEntity symbol) {
        return symbol.getQualifiedName();
    }

    /**
     * LLM이 파일 트리/근거 위치를 잃지 않도록 source_file 메타를 채운다.
     * - sourceFile 연관이 없으면 빈 문자열로 내려서 null 분기 비용을 줄인다.
     */
    private String resolveSourceFilePath(SymbolEntity symbol) {
        if (symbol.getSourceFile() == null || symbol.getSourceFile().getPath() == null) {
            return "";
        }
        return symbol.getSourceFile().getPath();
    }

    private String extractSimpleName(String qualifiedOrSymbol) {
        if (qualifiedOrSymbol == null || qualifiedOrSymbol.isBlank()) return "";
        int idx = Math.max(qualifiedOrSymbol.lastIndexOf('.'), qualifiedOrSymbol.lastIndexOf('#'));
        String name = idx >= 0 ? qualifiedOrSymbol.substring(idx + 1) : qualifiedOrSymbol;
        // strip symbol prefix e.g. "type:", "method:"
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /**
     * apiguardian @API status 감지 — NONE / STABLE / MAINTAINED / EXPERIMENTAL / INTERNAL / DEPRECATED
     *
     * 계층 매칭 정책(#7):
     * 1. 텍스트 형태 어노테이션에 "apiguardian" 포함 → STABLE 기본 (텍스트엔 status 속성 없음)
     * 2. 객체 형태 + FQN에 "apiguardian" 포함 → 진짜 apiguardian; status 없으면 STABLE 기본
     * 3. 객체 형태 + simpleName "api"/"Api" → status가 존재하고 유효 apiguardian status일 때만 인정
     * 4. status 없는 simpleName-only 매칭 → NONE (Swagger @Api 오탐 방지)
     */
    private String detectApiGuardianStatus(SymbolEntity symbol) {
        JsonNode annotations = symbol.getAnnotations();
        if (annotations == null || !annotations.isArray()) return "NONE";
        for (JsonNode ann : annotations) {
            if (ann.isTextual()) {
                if (ann.asText("").toLowerCase(Locale.ROOT).contains(API_GUARDIAN_ANNOTATION_FRAGMENT)) {
                    return "STABLE";
                }
                // simpleName "api"만인 텍스트는 status 속성 없으므로 인정 불가
            } else if (ann.isObject()) {
                String annName = resolveAnnotationName(ann);
                String annNameLower = annName.toLowerCase(Locale.ROOT);
                if (annNameLower.contains(API_GUARDIAN_ANNOTATION_FRAGMENT)) {
                    // FQN에 "apiguardian" 패키지 포함 → 진짜 apiguardian
                    String status = resolveAnnotationStatus(ann);
                    return status.isBlank() ? "STABLE" : status.toUpperCase(Locale.ROOT);
                }
                if ("api".equals(annNameLower)) {
                    // simpleName "api"/"Api" → status 있고 유효 apiguardian status여야만 인정
                    String status = resolveAnnotationStatus(ann).toUpperCase(Locale.ROOT);
                    return APIGUARDIAN_STATUSES.contains(status) ? status : "NONE";
                }
            }
        }
        return "NONE";
    }

    private String resolveAnnotationStatus(JsonNode ann) {
        String status = ann.path("status").asText("");
        if (!status.isBlank()) return status;
        status = ann.path("attributes").path("status").asText("");
        if (!status.isBlank()) return status;
        return ann.path("params").path("status").asText("");
    }

    private String resolveAnnotationName(JsonNode ann) {
        // FQN 키를 우선 시도해 Swagger @Api 등 simpleName 동명 어노테이션과 구분한다.
        // facts 어노테이션 노드는 FQN을 "raw" 키에 담는다(JavaParser 추출 스키마).
        for (String key : List.of("fqn", "qualifiedName", "raw", "name", "type")) {
            String val = ann.path(key).asText("");
            if (!val.isBlank()) return val;
        }
        return "";
    }

    private String extractAnnotationNameRaw(JsonNode ann) {
        return ann.isTextual() ? ann.asText("") : resolveAnnotationName(ann);
    }

    /**
     * #4: Spring DI 빈 종류 감지.
     * - STEREOTYPE: @Service / @Component / @Repository → PUBLIC_CONSTRUCTOR 무력화 + 진입 신호 없으면 NONE
     * - CONFIGURATION: @Configuration(단독) → 감점. @AutoConfiguration 포함 시 NONE(강등 금지)
     */
    private BeanKind detectBeanKind(SymbolEntity symbol) {
        JsonNode annotations = symbol.getAnnotations();
        if (annotations == null || !annotations.isArray()) return BeanKind.NONE;
        boolean hasConfiguration = false;
        for (JsonNode ann : annotations) {
            String lower = extractAnnotationNameRaw(ann).toLowerCase(Locale.ROOT);
            String simple = extractSimpleName(lower);
            if ("service".equals(simple) || "component".equals(simple) || "repository".equals(simple)) {
                return BeanKind.STEREOTYPE;
            }
            if (lower.contains("autoconfiguration")) {
                return BeanKind.NONE; // @AutoConfiguration → 공개 자동설정 진입점, 강등 금지
            }
            if ("configuration".equals(simple)) {
                hasConfiguration = true;
            }
        }
        return hasConfiguration ? BeanKind.CONFIGURATION : BeanKind.NONE;
    }

    private void registerModuleRoots(Map<String, ModuleRole> bySourceRoot, JsonNode roots, ModuleRole role) {
        if (!roots.isArray()) return;
        for (JsonNode root : roots) {
            String normalized = normalizeSourceRoot(root.asText(""));
            if (!normalized.isBlank()) {
                bySourceRoot.putIfAbsent(normalized, role);
            }
        }
    }

    private String normalizeSourceRoot(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/').trim();
        int repoIdx = normalized.lastIndexOf("/repo/");
        if (repoIdx >= 0) {
            normalized = normalized.substring(repoIdx + "/repo/".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    /**
     * #5: 어노테이션 타입 전용 점수.
     *
     * PUBLIC_ANNOTATION_TYPE (+2): 공개 @interface는 라이브러리 API 표면 자체.
     * META_ANNOTATION_RUNTIME (+2): @Retention(RUNTIME) + @Target 보유 — 런타임 처리 대상의 강한 증거.
     * ANNOTATION_USAGE_FREQ (+1): ANNOTATED_WITH 인바운드 ≥ 1 (실제 사용 빈도 신호).
     *
     * 비-어노테이션 typeKind이면 0점 반환.
     */
    private int phaseAnnotationScore(SymbolEntity symbol,
                                      Map<String, Integer> annotationUsageCounts,
                                      List<String> signals) {
        if (!"annotation".equals(resolveTypeKind(symbol))) return 0;

        int score = 0;
        signals.add("PUBLIC_ANNOTATION_TYPE");
        score += 2;

        if (hasRetentionRuntime(symbol) && hasTargetAnnotation(symbol)) {
            signals.add("META_ANNOTATION_RUNTIME");
            score += 2;
        }

        if (annotationUsageCounts.getOrDefault(symbol.getSymbolId(), 0) >= 1) {
            signals.add("ANNOTATION_USAGE_FREQ");
            score += 1;
        }

        return score;
    }

    /**
     * @interface 자신의 어노테이션 중 @Retention(RUNTIME) 존재 여부.
     * "value" / "attributes.value" / "params.value" 세 위치 모두 탐색.
     */
    private boolean hasRetentionRuntime(SymbolEntity symbol) {
        JsonNode annotations = symbol.getAnnotations();
        if (annotations == null || !annotations.isArray()) return false;
        for (JsonNode ann : annotations) {
            String rawName = extractAnnotationNameRaw(ann).toLowerCase(Locale.ROOT);
            if (!extractSimpleName(rawName).equals("retention")) continue;
            String val = resolveRetentionValue(ann).toUpperCase(Locale.ROOT);
            if ("RUNTIME".equals(val)) return true;
        }
        return false;
    }

    /** 어노테이션의 value 파라미터를 탐색한다 (RUNTIME/CLASS/SOURCE 판정용). */
    private String resolveRetentionValue(JsonNode ann) {
        if (ann.isTextual()) return "";
        for (String path : List.of("value", "attributes/value", "params/value")) {
            String[] parts = path.split("/");
            JsonNode node = ann;
            for (String part : parts) node = node.path(part);
            String text = node.isArray() && node.size() > 0
                    ? node.get(0).asText("") : node.asText("");
            if (!text.isBlank()) return text;
        }
        return "";
    }

    /** @interface 자신의 어노테이션 중 @Target 존재 여부. */
    private boolean hasTargetAnnotation(SymbolEntity symbol) {
        JsonNode annotations = symbol.getAnnotations();
        if (annotations == null || !annotations.isArray()) return false;
        for (JsonNode ann : annotations) {
            String rawName = extractAnnotationNameRaw(ann).toLowerCase(Locale.ROOT);
            if (extractSimpleName(rawName).equals("target")) return true;
        }
        return false;
    }

    /**
     * #6: 심볼의 근거 추출 완전성 메타를 계산한다.
     * sourceAvailable / javadocAvailable / annotationsAvailable / buildMode 필드를 채운다.
     * degraded 여부는 evaluatePhases()에서 결정해 toBuilder()로 갱신한다.
     */
    private EntryPointCandidate.EvidenceCompleteness buildEvidenceCompleteness(
            SymbolEntity symbol, ModuleRoleIndex moduleRoleIndex) {
        boolean sourceAvailable = symbol.getSourceFile() != null
                && symbol.getSourceFile().getPath() != null
                && !symbol.getSourceFile().getPath().isBlank();
        return EntryPointCandidate.EvidenceCompleteness.builder()
                .sourceAvailable(sourceAvailable)
                .javadocAvailable(isJavadocAvailable(symbol))
                .annotationsAvailable(isAnnotationsAvailable(symbol))
                .buildMode(moduleRoleIndex.resolveBuildMode(symbol))
                .degraded(false)
                .build();
    }

    private boolean isJavadocAvailable(SymbolEntity symbol) {
        JsonNode sig = symbol.getSignature();
        if (sig == null || sig.isNull()) return false;
        return !sig.path("javadoc").asText("").isBlank();
    }

    private boolean isAnnotationsAvailable(SymbolEntity symbol) {
        JsonNode anns = symbol.getAnnotations();
        return anns != null && anns.isArray() && !anns.isEmpty();
    }

    /**
     * #2: TYPE 진입점의 진입 메서드 목록을 결정한다.
     * static factory → public static → public instance 우선순위로 최대 MAX_ENTRY_METHODS개 반환.
     */
    private List<EntryPointCandidate.EntryMethodInfo> resolveEntryMethods(
            String symbolId,
            ChildMaps childMaps,
            SemanticEntrySignals semanticEntrySignals) {
        List<SymbolEntity> allMethods = childMaps.methodsByOwner()
                .getOrDefault(symbolId, List.of());
        List<SymbolEntity> publicMethods = allMethods.stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC)
                .toList();

        // Spring MVC handler는 Java access modifier와 무관하게 외부 HTTP 진입점이 될 수 있으므로
        // semantic endpoint는 전체 member에서 찾고, 기존 일반 API 휴리스틱만 public으로 제한한다.
        Map<String, SymbolEntity> byId = allMethods.stream()
                .collect(Collectors.toMap(SymbolEntity::getSymbolId, m -> m, (left, right) -> left));
        List<EntryPointCandidate.EntryMethodInfo> result = new ArrayList<>();
        Set<String> included = new HashSet<>();

        // HTTP endpoint는 실제 외부 진입 메서드이므로 일반 factory/static 휴리스틱보다 우선한다.
        for (String methodId : semanticEntrySignals.endpointMethodIds(symbolId)) {
            SymbolEntity method = byId.get(methodId);
            if (method == null || !included.add(methodId)) continue;
            result.add(EntryPointCandidate.EntryMethodInfo.builder()
                    .symbolId(method.getSymbolId())
                    .simpleName(method.getSimpleName())
                    .reason("HTTP_ENDPOINT")
                    .httpEndpoints(semanticEntrySignals.endpointInfos(method.getSymbolId()))
                    .build());
            if (result.size() >= MAX_ENTRY_METHODS) return List.copyOf(result);
        }

        publicMethods.stream()
                .filter(m -> !included.contains(m.getSymbolId()))
                .sorted(Comparator.comparingInt(this::entryMethodSeedPriority))
                .limit(MAX_ENTRY_METHODS - result.size())
                .map(m -> EntryPointCandidate.EntryMethodInfo.builder()
                        .symbolId(m.getSymbolId())
                        .simpleName(m.getSimpleName())
                        .reason(classifyEntryMethodReason(m))
                        .build())
                .forEach(result::add);

        return List.copyOf(result);
    }

    private String classifyEntryMethodReason(SymbolEntity method) {
        if (isStaticMethod(method) && isFactoryMethodName(method.getSimpleName())) return "STATIC_FACTORY";
        if (isStaticMethod(method)) return "PUBLIC_STATIC";
        return "PUBLIC_INSTANCE";
    }

    private int entryMethodSeedPriority(SymbolEntity method) {
        if (isStaticMethod(method) && isFactoryMethodName(method.getSimpleName())) return 0;
        if (isStaticMethod(method)) return 1;
        return 2;
    }

    /** 강도 비교용 등급. HIGH > MED > LOW. 알 수 없는 값은 0. 대소문자·공백 무시. */
    public static int confidenceRank(String confidence) {
        if (confidence == null) return 0;
        return switch (confidence.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 3;
            case "MED"  -> 2;
            case "LOW"  -> 1;
            default     -> 0;
        };
    }

    private int confidenceOrder(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 0;
            case "MED"  -> 1;
            default     -> 2;
        };
    }

    // ─── 내부 레코드 ─────────────────────────────────────────────────────────

    private record ChildMaps(
            Map<String, List<SymbolEntity>> constructorsByOwner,
            Map<String, List<SymbolEntity>> methodsByOwner,
            Map<String, String>             methodOwnerIndex
    ) {}

    private record SemanticEntrySignals(
            Set<String> usableOwnerIds,
            Set<String> highTrustOwnerIds,
            Map<String, List<String>> endpointMethodIdsByOwner,
            Map<String, List<EntryPointCandidate.HttpEndpointInfo>> endpointInfosByMethodId
    ) {
        private boolean hasUsableEndpoint(String symbolId) {
            return symbolId != null && usableOwnerIds.contains(symbolId);
        }

        private boolean hasHighTrustEndpoint(String symbolId) {
            return symbolId != null && highTrustOwnerIds.contains(symbolId);
        }

        private List<String> endpointMethodIds(String symbolId) {
            return symbolId == null ? List.of()
                    : endpointMethodIdsByOwner.getOrDefault(symbolId, List.of());
        }

        private List<EntryPointCandidate.HttpEndpointInfo> endpointInfos(String methodId) {
            return methodId == null ? List.of()
                    : endpointInfosByMethodId.getOrDefault(methodId, List.of());
        }
    }

    private record FactsSignals(
            Set<String> readmeMentionedFqns,
            Set<String> readmeMentionedSimpleNames,
            Map<String, Long> projectSimpleNameCount,
            Set<String> exampleReferencedSimpleNames
    ) {}

    private record PhaseResult(
            String confidence,
            String role,
            List<String> signals,
            int score,
            EntryPointCandidate.EvidenceCompleteness evidenceCompleteness
    ) {}

    private record ModuleRole(boolean exampleModule, boolean publishedLibrary, String buildMode) {}

    private record ModuleRoleIndex(Map<String, ModuleRole> bySourceRoot) {
        private static ModuleRoleIndex empty() {
            return new ModuleRoleIndex(Map.of());
        }

        private boolean isExampleModule(SymbolEntity symbol) {
            ModuleRole role = resolve(symbol);
            return role != null && role.exampleModule();
        }

        private boolean isPublishedLibrary(SymbolEntity symbol) {
            ModuleRole role = resolve(symbol);
            return role != null && role.publishedLibrary();
        }

        private String resolveBuildMode(SymbolEntity symbol) {
            ModuleRole role = resolve(symbol);
            return role != null && role.buildMode() != null ? role.buildMode() : "UNKNOWN";
        }

        private ModuleRole resolve(SymbolEntity symbol) {
            if (symbol == null || symbol.getSourceRoot() == null) return null;
            return bySourceRoot.get(normalize(symbol.getSourceRoot()));
        }

        private static String normalize(String path) {
            if (path == null || path.isBlank()) return "";
            String normalized = path.replace('\\', '/').trim();
            int repoIdx = normalized.lastIndexOf("/repo/");
            if (repoIdx >= 0) {
                normalized = normalized.substring(repoIdx + "/repo/".length());
            }
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            return normalized;
        }
    }

    private enum BeanKind { NONE, STEREOTYPE, CONFIGURATION }
}
