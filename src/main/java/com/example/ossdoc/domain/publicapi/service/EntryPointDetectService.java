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
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
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

/**
 * 역할: EntryPointPlan.md 파이프라인 구현체.
 *
 * Phase 1 (HIGH 신호): README 언급, Javadoc @apiNote 키워드, 예제 코드 참조
 * Phase 2 (점수 누적): public 생성자 / static factory / builder
 * Phase 3 (점수 누적): 네이밍 컨벤션, facade 구조(public 메서드 수)
 * Phase 4 (점수 누적): 다른 public API 반환 타입 등장 빈도
 *
 * confidence: Phase 1 신호 ≥ 1 → HIGH / score ≥ 3 → MED / score ≥ 1 → LOW
 * role: SECONDARY (returned by public API, 직접 생성 경로 없음) / PRIMARY (그 외)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntryPointDetectService {

    private static final Set<String> EXCLUDED_PKG_SEGMENTS = Set.of(
            "internal", "impl", "util", "utils", "helper", "helpers"
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
    private static final List<String> JAVADOC_KEYWORDS = List.of(
            "main entry point", "primary api", "use this class",
            "start with", "bootstrap", "configure", "@apiNote"
    );
    private static final int MIN_FACADE_METHODS = 5;
    private static final int MED_MIN_SCORE = 3;

    private final PublicApiEntryRepository publicApiEntryRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactRepository artifactRepository;

    public List<EntryPointCandidate> detect(String runId) {
        Set<String> publicSymbolIds = loadPublicSymbolIds(runId);
        if (publicSymbolIds.isEmpty()) {
            return List.of();
        }

        // 모든 심볼 한 번에 로드 → first-level cache로 lazy 연관 N+1 방지
        List<SymbolEntity> allSymbols = symbolRepository.findAllByRun_RunId(runId);
        ChildMaps childMaps = buildChildMaps(allSymbols);

        Map<String, Integer> returnedByPublicApi =
                countReturnedByPublicApi(runId, publicSymbolIds, childMaps.methodOwnerIndex());

        FactsSignals factsSignals = loadFactsSignals(runId);
        SubsystemMaps subsystemMaps = loadSubsystemMaps(runId);
        Set<String> exportedPackages = loadExportedPackages(allSymbols);

        List<EntryPointCandidate> candidates = new ArrayList<>();

        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.TYPE) continue;
            if (!publicSymbolIds.contains(symbol.getSymbolId())) continue;
            if (shouldExclude(symbol, exportedPackages, childMaps)) continue;

            PhaseResult result = evaluatePhases(
                    symbol, childMaps, returnedByPublicApi, factsSignals);
            if ("NONE".equals(result.confidence())) continue;

            String subsystemId    = subsystemMaps.memberToSubsystem().get(symbol.getSymbolId());
            String subsystemLabel = subsystemId != null
                    ? subsystemMaps.labelBySubsystem().get(subsystemId) : null;

            candidates.add(EntryPointCandidate.builder()
                    .symbolId(symbol.getSymbolId())
                    .qualifiedName(symbol.getQualifiedName())
                    .simpleName(symbol.getSimpleName())
                    .typeKind(resolveTypeKind(symbol))
                    .subsystemId(subsystemId)
                    .subsystemLabel(subsystemLabel)
                    .role(result.role())
                    .confidence(result.confidence())
                    .signals(List.copyOf(result.signals()))
                    .score(result.score())
                    .build());
        }

        candidates.sort(Comparator
                .comparingInt((EntryPointCandidate c) -> confidenceOrder(c.getConfidence()))
                .thenComparingInt(c -> -c.getScore()));

        return List.copyOf(candidates);
    }

    // ─── 데이터 로딩 ────────────────────────────────────────────────────────────

    private Set<String> loadPublicSymbolIds(String runId) {
        List<PublicApiEntry> entries = publicApiEntryRepository.findAllByRun_RunId(runId);
        Set<String> ids = new HashSet<>(entries.size());
        for (PublicApiEntry entry : entries) {
            ids.add(entry.getSymbol().getSymbolId());
        }
        return ids;
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
     * observations 배열에서 kind 가 "readme_" 계열인 항목을 README 신호로 인식한다.
     * evidence 배열에서 파일 경로가 example/sample/demo 계열이면 예제 코드 신호로 인식한다.
     */
    private FactsSignals loadFactsSignals(String runId) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON);
        if (opt.isEmpty()) {
            return new FactsSignals(Set.of(), Set.of());
        }

        JsonNode meta = opt.get().getMeta();
        Set<String> readmeMentioned   = new HashSet<>();
        Set<String> exampleReferenced = new HashSet<>();

        JsonNode observations = meta.path("observations");
        if (observations.isArray()) {
            for (JsonNode obs : observations) {
                String kind   = obs.path("kind").asText("").toLowerCase(Locale.ROOT);
                String symbol = obs.path("symbol").asText("");
                if (kind.startsWith("readme") || kind.contains("quickstart") || kind.contains("usage")) {
                    String simpleName = extractSimpleName(symbol);
                    if (!simpleName.isEmpty()) readmeMentioned.add(simpleName);
                }
            }
        }

        JsonNode evidenceArray = meta.path("evidence");
        if (evidenceArray.isArray()) {
            for (JsonNode ev : evidenceArray) {
                String filePath = ev.path("file").asText("")
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
                Set.copyOf(readmeMentioned),
                Set.copyOf(exampleReferenced));
    }

    private SubsystemMaps loadSubsystemMaps(String runId) {
        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON);
        if (opt.isEmpty()) return new SubsystemMaps(Map.of(), Map.of());

        JsonNode meta       = opt.get().getMeta();
        JsonNode subsystems = meta.path("subsystems");
        if (!subsystems.isArray()) return new SubsystemMaps(Map.of(), Map.of());

        Map<String, String> memberToSubsystem = new HashMap<>();
        Map<String, String> labelBySubsystem  = new HashMap<>();

        for (JsonNode ss : subsystems) {
            String subsystemId = ss.path("subsystemId").asText(null);
            String name        = ss.path("name").asText(null);
            if (subsystemId == null) continue;
            if (name != null) labelBySubsystem.put(subsystemId, name);
            JsonNode members = ss.path("memberSymbolIds");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    memberToSubsystem.put(m.asText(), subsystemId);
                }
            }
        }
        return new SubsystemMaps(memberToSubsystem, labelBySubsystem);
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

    // ─── 필터 ────────────────────────────────────────────────────────────────

    /**
     * 제외 조건 하나라도 해당하면 true.
     *
     * 1. internal / impl / util / helper 패키지 세그먼트
     * 2. JPMS 범위 필터 (module-info.java exports 미포함 패키지)
     * 3. @Deprecated
     * 4. abstract class (인터페이스 제외)
     * 5. public 생성자도 없고 static factory도 없는 class / record
     */
    private boolean shouldExclude(SymbolEntity symbol,
                                   Set<String> exportedPackages,
                                   ChildMaps childMaps) {
        String qualifiedName = symbol.getQualifiedName();

        if (hasExcludedPackageSegment(qualifiedName)) return true;

        if (!exportedPackages.isEmpty()) {
            String pkg = extractPackageName(qualifiedName);
            if (!exportedPackages.contains(pkg)) return true;
        }

        if (isDeprecated(symbol)) return true;

        String typeKind = resolveTypeKind(symbol);

        if ("class".equals(typeKind) && hasAbstractModifier(symbol.getModifiers())) return true;

        if (("class".equals(typeKind) || "record".equals(typeKind))) {
            List<SymbolEntity> constructors =
                    childMaps.constructorsByOwner().getOrDefault(symbol.getSymbolId(), List.of());
            List<SymbolEntity> methods =
                    childMaps.methodsByOwner().getOrDefault(symbol.getSymbolId(), List.of());
            if (!hasInstantiationPath(constructors, methods)) return true;
        }

        return false;
    }

    // ─── 페이즈 평가 ──────────────────────────────────────────────────────────

    private PhaseResult evaluatePhases(SymbolEntity symbol,
                                        ChildMaps childMaps,
                                        Map<String, Integer> returnedByPublicApi,
                                        FactsSignals factsSignals) {
        List<String> signals  = new ArrayList<>();
        String simpleName     = symbol.getSimpleName();
        String symbolId       = symbol.getSymbolId();

        // Phase 1 — 문서 직접 언급 신호 (HIGH 즉시 확정)
        if (simpleName != null && factsSignals.readmeMentionedSimpleNames().contains(simpleName)) {
            signals.add("README_MENTION");
        }
        if (hasJavadocEntryPointSignal(symbol)) {
            signals.add("JAVADOC_ENTRY_POINT");
        }
        if (simpleName != null && factsSignals.exampleReferencedSimpleNames().contains(simpleName)) {
            signals.add("EXAMPLE_CODE_REFERENCE");
        }

        if (!signals.isEmpty()) {
            int bonus = phase2Score(symbolId, childMaps, signals)
                    + phase3Score(symbol, childMaps, signals);
            String role = determineRole(symbolId, signals, returnedByPublicApi);
            return new PhaseResult("HIGH", role, signals, 10 + bonus);
        }

        // Phase 2 ~ 4 — 점수 누적
        int score = phase2Score(symbolId, childMaps, signals)
                + phase3Score(symbol, childMaps, signals)
                + phase4Score(symbolId, returnedByPublicApi, signals);

        if (score == 0) return new PhaseResult("NONE", null, signals, 0);

        String confidence = score >= MED_MIN_SCORE ? "MED" : "LOW";
        String role       = determineRole(symbolId, signals, returnedByPublicApi);
        return new PhaseResult(confidence, role, signals, score);
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
     * HIGH 이름 접미사 +2, MED +1, facade(public 메서드 ≥ 5) +1.
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

        long publicMethodCount = childMaps.methodsByOwner()
                .getOrDefault(symbol.getSymbolId(), List.of()).stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC)
                .count();
        if (publicMethodCount >= MIN_FACADE_METHODS) {
            signals.add("FACADE_STRUCTURE");
            score += 1;
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
     * Secondary 조건: 다른 public API가 반환하고, 직접 생성 경로 신호가 없는 경우.
     */
    private String determineRole(String symbolId,
                                  List<String> signals,
                                  Map<String, Integer> returnedByPublicApi) {
        boolean returnedByApi    = returnedByPublicApi.getOrDefault(symbolId, 0) >= 1;
        boolean hasDirectCreate  = signals.contains("PUBLIC_CONSTRUCTOR")
                || signals.contains("STATIC_FACTORY")
                || signals.contains("README_MENTION");
        return (returnedByApi && !hasDirectCreate) ? "SECONDARY" : "PRIMARY";
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────────

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
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : null;
    }

    private boolean isDeprecated(SymbolEntity symbol) {
        JsonNode sig = symbol.getSignature();
        if (sig != null && !sig.isNull()) {
            JsonNode annotations = sig.path("annotations");
            if (annotations.isArray()) {
                for (JsonNode ann : annotations) {
                    String name = ann.asText("").toLowerCase(Locale.ROOT);
                    if (name.contains("deprecated")) return true;
                }
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
        JsonNode sig = symbol.getSignature();
        if (sig != null && !sig.isNull()) {
            String typeKind = sig.path("typeKind").asText("");
            if (!typeKind.isBlank()) return typeKind.toLowerCase(Locale.ROOT);
        }
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

    private String extractSimpleName(String qualifiedOrSymbol) {
        if (qualifiedOrSymbol == null || qualifiedOrSymbol.isBlank()) return "";
        int idx = Math.max(qualifiedOrSymbol.lastIndexOf('.'), qualifiedOrSymbol.lastIndexOf('#'));
        String name = idx >= 0 ? qualifiedOrSymbol.substring(idx + 1) : qualifiedOrSymbol;
        // strip symbol prefix e.g. "type:", "method:"
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
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

    private record FactsSignals(
            Set<String> readmeMentionedSimpleNames,
            Set<String> exampleReferencedSimpleNames
    ) {}

    private record SubsystemMaps(
            Map<String, String> memberToSubsystem,
            Map<String, String> labelBySubsystem
    ) {}

    private record PhaseResult(
            String confidence,
            String role,
            List<String> signals,
            int score
    ) {}
}
