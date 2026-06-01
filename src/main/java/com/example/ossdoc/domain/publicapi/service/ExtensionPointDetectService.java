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
import com.example.ossdoc.domain.publicapi.model.ExtensionPointCandidate;
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
 * 역할: ExtensionPointPlan.md 파이프라인 구현체.
 *
 * Phase 1 (HIGH 즉시 확정): SPI(META-INF/services), module-info.java uses 선언
 * Phase 2 (점수 누적): Javadoc @implSpec / @apiNote / 확장 키워드
 * Phase 3 (점수 누적): 패키지 이름(spi/extension/plugin/provider), 타입 이름 패턴
 * Phase 4 (점수 누적): 등록·주입 메서드 param, @FunctionalInterface, abstract contract test
 *
 * confidence: Phase 1 → HIGH / 복수 신호 조합(score ≥ 6) → HIGH / score ≥ 3 → MED / score ≥ 1 → LOW
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExtensionPointDetectService {

    // ─── 제외 패키지 세그먼트 ────────────────────────────────────────────────
    private static final Set<String> EXCLUDED_PKG_SEGMENTS = Set.of(
            "internal", "impl"
    );
    private static final Set<String> EXCLUDED_PATH_MARKERS = Set.of(
            "src/test/", "src/it/", "src/integrationtest/", "src/integration-test/",
            "/example/", "/examples/", "/sample/", "/samples/", "/demo/", "/demos/"
    );

    // ─── Phase 2 Javadoc ─────────────────────────────────────────────────────
    private static final List<String> JAVADOC_STRONG_KEYWORDS = List.of(
            "@implspec", "implement", "extend", "subclass", "override",
            "provider", "plugin", "spi", "custom implementation",
            "register", "hook", "callback", "listener"
    );

    // ─── Phase 3 패키지 신호 ────────────────────────────────────────────────
    private static final Map<String, Integer> PKG_SEGMENT_SCORES = Map.of(
            "spi",       3,
            "extension", 3,
            "extensions",3,
            "plugin",    3,
            "plugins",   3,
            "provider",  2,
            "providers", 2,
            "api",       1
    );

    // ─── Phase 3 이름 패턴 ───────────────────────────────────────────────────
    private static final Set<String> HIGH_NAME_SUFFIX = Set.of(
            "plugin", "extension", "provider", "listener",
            "callback", "interceptor", "filter"
    );
    private static final Set<String> MED_NAME_SUFFIX = Set.of(
            "strategy", "policy", "handler", "processor", "factory"
    );

    // ─── Phase 4 등록 메서드 접두사 ─────────────────────────────────────────
    private static final Set<String> REGISTER_METHOD_PREFIXES = Set.of(
            "add", "register", "set", "with", "install", "configure", "bind", "attach"
    );

    // ─── Confidence 임계값 ───────────────────────────────────────────────────
    private static final int HIGH_MIN_SCORE = 6;
    private static final int MED_MIN_SCORE  = 3;

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactRepository artifactRepository;

    public List<ExtensionPointCandidate> detect(String runId) {
        List<SymbolEntity> allSymbols = symbolRepository.findAllByRun_RunId(runId);
        Set<String> publicSymbolIds = loadPublicSymbolIds(allSymbols);
        if (publicSymbolIds.isEmpty()) {
            return List.of();
        }

        ChildMaps childMaps = buildChildMaps(allSymbols);

        Map<String, Integer> implementorCounts =
                countInboundEdges(runId, EdgeType.IMPLEMENTS);
        Map<String, Integer> extenderCounts =
                countInboundEdges(runId, EdgeType.EXTENDS);

        // Phase 4 — 등록·주입 메서드 param 집계
        Map<String, Integer> registrationParamCounts =
                countRegistrationParams(runId, publicSymbolIds, childMaps.methodOwnerIndex());

        // Phase 1 신호 소스: FACTS_JSON 아티팩트
        FactsSignals factsSignals = loadFactsSignals(runId, allSymbols);

        SubsystemMaps subsystemMaps = loadSubsystemMaps(runId);

        List<ExtensionPointCandidate> candidates = new ArrayList<>();

        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.TYPE) continue;
            if (!publicSymbolIds.contains(symbol.getSymbolId())) continue;

            int implementorCount = implementorCounts.getOrDefault(symbol.getSymbolId(), 0);
            int extenderCount    = extenderCounts.getOrDefault(symbol.getSymbolId(), 0);

            if (shouldExclude(symbol, implementorCount, registrationParamCounts)) continue;

            PhaseResult result = evaluatePhases(
                    symbol, implementorCount, extenderCount,
                    registrationParamCounts, factsSignals);
            if ("NONE".equals(result.confidence())) continue;

            String subsystemId    = subsystemMaps.memberToSubsystem().get(symbol.getSymbolId());
            String subsystemLabel = subsystemId != null
                    ? subsystemMaps.labelBySubsystem().get(subsystemId) : null;

            String typeKind = resolveTypeKind(symbol, implementorCount);

            candidates.add(ExtensionPointCandidate.builder()
                    .symbolId(symbol.getSymbolId())
                    .qualifiedName(symbol.getQualifiedName())
                    .ownerTypeFqn(resolveOwnerTypeFqn(symbol))
                    .simpleName(symbol.getSimpleName())
                    .typeKind(typeKind)
                    .sourceFile(resolveSourceFilePath(symbol))
                    .startLine(symbol.getSourceStartLine())
                    .endLine(symbol.getSourceEndLine())
                    .subsystemId(subsystemId)
                    .subsystemLabel(subsystemLabel)
                    .linkedImplementorCount(implementorCount)
                    .linkedExtenderCount(extenderCount)
                    .confidence(result.confidence())
                    .signals(List.copyOf(result.signals()))
                    .score(result.score())
                    .build());
        }

        candidates.sort(Comparator
                .comparingInt((ExtensionPointCandidate c) -> confidenceOrder(c.getConfidence()))
                .thenComparingInt(c -> -c.getScore()));

        return List.copyOf(candidates);
    }

    // ─── 데이터 로딩 ────────────────────────────────────────────────────────

    private Set<String> loadPublicSymbolIds(List<SymbolEntity> allSymbols) {
        // allSymbols는 detect() 상단에서 이미 로드됨 — 추가 DB 쿼리 없음
        // 판정 기준은 PublicSymbolFilter에서 단일 관리 (sync와 parity 보장)
        return allSymbols.stream()
                .filter(PublicSymbolFilter::isPublicApiType)
                .map(SymbolEntity::getSymbolId)
                .collect(Collectors.toSet());
    }

    private ChildMaps buildChildMaps(List<SymbolEntity> allSymbols) {
        Map<String, List<SymbolEntity>> methodsByOwner = new HashMap<>();
        Map<String, String>            methodOwnerIndex = new HashMap<>();

        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.METHOD) continue;
            if (symbol.getOwner() == null) continue;

            String ownerSymbolId = symbol.getOwner().getSymbolId();
            methodsByOwner
                    .computeIfAbsent(ownerSymbolId, k -> new ArrayList<>())
                    .add(symbol);
            methodOwnerIndex.put(symbol.getSymbolId(), ownerSymbolId);
        }
        return new ChildMaps(methodsByOwner, methodOwnerIndex);
    }

    private Map<String, Integer> countInboundEdges(String runId, EdgeType edgeType) {
        List<Edge> edges = edgeRepository
                .findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, edgeType);
        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : edges) {
            counts.merge(edge.getToSymbol().getSymbolId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * PARAM 엣지에서 등록·주입 메서드(add/register/set/with 계열)가
     * 이 타입을 파라미터로 받는 빈도를 집계한다.
     *
     * PARAM 엣지 구조: METHOD(fromSymbol) --PARAM--> TYPE(toSymbol)
     */
    private Map<String, Integer> countRegistrationParams(String runId,
                                                          Set<String> publicSymbolIds,
                                                          Map<String, String> methodOwnerIndex) {
        List<Edge> paramEdges = edgeRepository
                .findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.PARAM);

        Map<String, Integer> counts = new HashMap<>();
        for (Edge edge : paramEdges) {
            String methodId   = edge.getFromSymbol().getSymbolId();
            String ownerSymId = methodOwnerIndex.get(methodId);
            if (ownerSymId == null || !publicSymbolIds.contains(ownerSymId)) continue;

            // 등록·주입 메서드 이름 패턴 확인
            String methodSymbolId = edge.getFromSymbol().getSymbolId();
            if (!isRegistrationMethodId(methodSymbolId)) continue;

            String paramTypeId = edge.getToSymbol().getSymbolId();
            counts.merge(paramTypeId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * symbolId 형식: "method:com.example.Foo#addListener" → 메서드명 추출 후 접두사 확인.
     */
    private boolean isRegistrationMethodId(String methodSymbolId) {
        if (methodSymbolId == null) return false;
        int hashIdx = methodSymbolId.lastIndexOf('#');
        String methodName = hashIdx >= 0
                ? methodSymbolId.substring(hashIdx + 1).toLowerCase(Locale.ROOT)
                : methodSymbolId.toLowerCase(Locale.ROOT);
        return REGISTER_METHOD_PREFIXES.stream().anyMatch(methodName::startsWith);
    }

    /**
     * FACTS_JSON 아티팩트에서 Phase 1 신호(SPI, module uses)와 Phase 4 신호(contract test)를 추출한다.
     *
     * SPI: observations.spi_providers 버킷에서 target_symbol(interface FQCN) 추출
     * module uses: MODULE 심볼의 signature.uses 배열
     * contract test: evidence 배열에서 test 경로의 Abstract*Test / *ContractTest 파일
     */
    private FactsSignals loadFactsSignals(String runId, List<SymbolEntity> allSymbols) {
        Set<String> spiQualifiedNames          = new HashSet<>();
        Set<String> moduleUsedQualifiedNames   = new HashSet<>();
        Set<String> contractTestSimpleNames    = new HashSet<>();

        // MODULE 심볼 signature.uses 파싱
        for (SymbolEntity symbol : allSymbols) {
            if (symbol.getSymbolKind() != SymbolKind.MODULE) continue;
            JsonNode sig  = symbol.getSignature();
            JsonNode uses = sig == null ? null : sig.path("uses");
            if (uses != null && uses.isArray()) {
                for (JsonNode u : uses) {
                    moduleUsedQualifiedNames.add(u.asText());
                }
            }
        }

        Optional<Artifact> opt = artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON);
        if (opt.isEmpty()) {
            return new FactsSignals(spiQualifiedNames, moduleUsedQualifiedNames, contractTestSimpleNames);
        }

        JsonNode meta = opt.get().getMeta();

        // observations.spi_providers 버킷: target_symbol = 인터페이스 FQCN
        JsonNode spiProviders = meta.path("observations").path("spi_providers");
        if (spiProviders.isArray()) {
            for (JsonNode obs : spiProviders) {
                String symbol = obs.path("target_symbol").asText("");
                if (!symbol.isBlank()) {
                    spiQualifiedNames.add(extractQualifiedName(symbol));
                }
            }
        }

        // evidence: Abstract contract test 탐지
        JsonNode evidenceArray = meta.path("evidence");
        if (evidenceArray.isArray()) {
            for (JsonNode ev : evidenceArray) {
                String filePath = ev.path("path").asText("")
                        .replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!filePath.contains("src/test/")) continue;

                String symbol = ev.path("symbol").asText("");
                String simpleName = extractSimpleName(symbol);
                if (isContractTestName(simpleName)) {
                    // 스니펫에 abstract create/build/make 메서드가 있는지 확인
                    String snippet = ev.path("snippet").asText("").toLowerCase(Locale.ROOT);
                    if (snippet.contains("abstract") &&
                            (snippet.contains("create") || snippet.contains("build") || snippet.contains("make"))) {
                        contractTestSimpleNames.add(simpleName);
                    }
                }
            }
        }

        return new FactsSignals(
                Set.copyOf(spiQualifiedNames),
                Set.copyOf(moduleUsedQualifiedNames),
                Set.copyOf(contractTestSimpleNames));
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

    // ─── 필터 ────────────────────────────────────────────────────────────────

    /**
     * 제외 조건:
     * 1. internal / impl 패키지 세그먼트
     * 2. test/example/sample/demo 경로
     * 3. final class
     * 4. sealed class
     * 5. @Deprecated
     * 6. 구현체 1개뿐이고 외부 주입 경로 없음 (주입 param 신호 없음)
     */
    private boolean shouldExclude(SymbolEntity symbol,
                                   int implementorCount,
                                   Map<String, Integer> registrationParamCounts) {
        String qualifiedName = symbol.getQualifiedName();

        if (hasExcludedPackageSegment(qualifiedName)) return true;
        if (hasExcludedSourcePath(symbol)) return true;
        if (isDeprecated(symbol)) return true;
        if (isFinalClass(symbol)) return true;
        if (isSealedClass(symbol)) return true;

        // 구현체 1개뿐이고 외부 주입 경로도 없으면 내부 계층 분리용으로 판단
        if (implementorCount == 1
                && registrationParamCounts.getOrDefault(symbol.getSymbolId(), 0) == 0) {
            return true;
        }

        return false;
    }

    // ─── 페이즈 평가 ──────────────────────────────────────────────────────────

    private PhaseResult evaluatePhases(SymbolEntity symbol,
                                        int implementorCount,
                                        int extenderCount,
                                        Map<String, Integer> registrationParamCounts,
                                        FactsSignals factsSignals) {
        List<String> signals = new ArrayList<>();
        String symbolId      = symbol.getSymbolId();
        String qualifiedName = symbol.getQualifiedName();

        // Phase 1 — 명시적 선언 신호 (HIGH 즉시)
        if (factsSignals.spiQualifiedNames().contains(qualifiedName)) {
            signals.add("SPI_DECLARED");
        }
        if (factsSignals.moduleUsedQualifiedNames().contains(qualifiedName)) {
            signals.add("MODULE_USES");
        }
        if (!signals.isEmpty()) {
            int bonus = phase2Score(symbol, signals)
                    + phase3Score(symbol, signals)
                    + phase4Score(symbol, registrationParamCounts, factsSignals, signals);
            return new PhaseResult("HIGH", signals, 10 + bonus);
        }

        // Phase 2 + 3 + 4
        int score = phase2Score(symbol, signals)
                + phase3Score(symbol, signals)
                + phase4Score(symbol, registrationParamCounts, factsSignals, signals);

        // IMPLEMENTS/EXTENDS 인바운드 존재 자체는 보조 신호 (점수 없이 근거만 기록)
        if (implementorCount >= 2) signals.add("MULTI_IMPLEMENTOR");
        if (extenderCount >= 2)    signals.add("MULTI_EXTENDER");

        if (score == 0 && implementorCount < 2 && extenderCount < 2) {
            return new PhaseResult("NONE", signals, 0);
        }

        String confidence;
        if (score >= HIGH_MIN_SCORE) {
            confidence = "HIGH";
        } else if (score >= MED_MIN_SCORE) {
            confidence = "MED";
        } else {
            confidence = "LOW";
        }
        return new PhaseResult(confidence, signals, score);
    }

    /**
     * Phase 2: Javadoc @implSpec (+3), @apiNote (+2), 확장 키워드 (+2)
     */
    private int phase2Score(SymbolEntity symbol, List<String> signals) {
        JsonNode sig = symbol.getSignature();
        if (sig == null || sig.isNull()) return 0;

        String javadoc = sig.path("javadoc").asText("").toLowerCase(Locale.ROOT);
        if (javadoc.isBlank()) return 0;

        int score = 0;
        if (javadoc.contains("@implspec")) {
            signals.add("JAVADOC_IMPLSPEC");
            score += 3;
        } else if (javadoc.contains("@apiNote")) {
            signals.add("JAVADOC_APINOTE");
            score += 2;
        }

        boolean hasKeyword = JAVADOC_STRONG_KEYWORDS.stream()
                .anyMatch(javadoc::contains);
        if (hasKeyword && score == 0) {
            // @implSpec/@apiNote 없이 키워드만 있는 경우
            signals.add("JAVADOC_KEYWORD");
            score += 2;
        } else if (hasKeyword) {
            // 이미 @implSpec/@apiNote 신호가 있으면 키워드는 보조 기록만
            signals.add("JAVADOC_KEYWORD");
        }

        return score;
    }

    /**
     * Phase 3: 패키지 이름 신호 + 타입 이름 패턴
     * spi/extension/plugin → +3, provider → +2, api → +1
     * 강한 이름 접미사 → +2, 중간 → +1
     */
    private int phase3Score(SymbolEntity symbol, List<String> signals) {
        int score        = 0;
        String qualified = symbol.getQualifiedName();

        // 패키지 세그먼트 신호
        String packageName = extractPackageName(qualified);
        if (packageName != null) {
            int pkgScore = 0;
            for (String segment : packageName.split("\\.")) {
                int s = PKG_SEGMENT_SCORES.getOrDefault(segment.toLowerCase(Locale.ROOT), 0);
                pkgScore = Math.max(pkgScore, s);
            }
            if (pkgScore > 0) {
                signals.add("PACKAGE_SIGNAL");
                score += pkgScore;
            }
        }

        // 타입 이름 접미사
        String name = symbol.getSimpleName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HIGH_NAME_SUFFIX.stream().anyMatch(lower::endsWith)) {
                signals.add("NAMING_HIGH");
                score += 2;
            } else if (MED_NAME_SUFFIX.stream().anyMatch(lower::endsWith)) {
                signals.add("NAMING_MED");
                score += 1;
            }
        }

        return score;
    }

    /**
     * Phase 4:
     * - 등록·주입 메서드 param 등장 → +3
     * - @FunctionalInterface 어노테이션 → +1
     * - Abstract contract test 존재 → +4
     */
    private int phase4Score(SymbolEntity symbol,
                              Map<String, Integer> registrationParamCounts,
                              FactsSignals factsSignals,
                              List<String> signals) {
        int score = 0;

        int registrationCount = registrationParamCounts.getOrDefault(symbol.getSymbolId(), 0);
        if (registrationCount >= 1) {
            signals.add("REGISTRATION_PARAM");
            score += 3;
        }

        if (hasFunctionalInterfaceAnnotation(symbol)) {
            signals.add("FUNCTIONAL_INTERFACE");
            score += 1;
        }

        // Abstract contract test: 이 타입의 simpleName 기반으로 "Abstract{SimpleName}Test" 파일 탐지
        String simpleName = symbol.getSimpleName();
        if (simpleName != null) {
            boolean hasContractTest = factsSignals.contractTestSimpleNames().stream()
                    .anyMatch(name -> name.toLowerCase(Locale.ROOT)
                            .contains(simpleName.toLowerCase(Locale.ROOT)));
            if (hasContractTest) {
                signals.add("CONTRACT_TEST");
                score += 4;
            }
        }

        return score;
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

    private boolean hasExcludedSourcePath(SymbolEntity symbol) {
        if (symbol.getSourceFile() == null) return false;
        String path = symbol.getSourceFile().getPath();
        if (path == null) return false;
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return EXCLUDED_PATH_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean isDeprecated(SymbolEntity symbol) {
        return hasAnnotationOrModifier(symbol, "deprecated");
    }

    private boolean isFinalClass(SymbolEntity symbol) {
        JsonNode modifiers = symbol.getModifiers();
        if (modifiers == null || !modifiers.isArray()) return false;
        for (JsonNode mod : modifiers) {
            if ("final".equalsIgnoreCase(mod.asText())) return true;
        }
        return false;
    }

    private boolean isSealedClass(SymbolEntity symbol) {
        JsonNode sig = symbol.getSignature();
        if (sig == null || sig.isNull()) return false;
        return sig.path("sealed").asBoolean(false);
    }

    private boolean hasFunctionalInterfaceAnnotation(SymbolEntity symbol) {
        return hasAnnotationOrModifier(symbol, "functionalinterface");
    }

    private boolean hasAnnotationOrModifier(SymbolEntity symbol, String target) {
        JsonNode modifiers = symbol.getModifiers();
        if (modifiers != null && modifiers.isArray()) {
            for (JsonNode mod : modifiers) {
                if (target.equalsIgnoreCase(mod.asText())) return true;
            }
        }
        JsonNode sig = symbol.getSignature();
        if (sig != null && !sig.isNull()) {
            JsonNode annotations = sig.path("annotations");
            if (annotations.isArray()) {
                for (JsonNode ann : annotations) {
                    if (ann.asText("").toLowerCase(Locale.ROOT).contains(target)) return true;
                }
            }
        }
        return false;
    }

    private String resolveTypeKind(SymbolEntity symbol, int implementorCount) {
        JsonNode sig = symbol.getSignature();
        if (sig != null && !sig.isNull()) {
            String typeKind = sig.path("typeKind").asText("").toLowerCase(Locale.ROOT);
            if ("interface".equals(typeKind)) return "interface";
            if ("abstract".equals(typeKind) || ("class".equals(typeKind) && hasAbstractModifier(symbol))) {
                return "abstract";
            }
        }
        // fallback: IMPLEMENTS 인바운드가 있으면 사실상 인터페이스
        return implementorCount >= 1 ? "interface" : "abstract";
    }

    private boolean hasAbstractModifier(SymbolEntity symbol) {
        JsonNode modifiers = symbol.getModifiers();
        if (modifiers == null || !modifiers.isArray()) return false;
        for (JsonNode mod : modifiers) {
            if ("abstract".equalsIgnoreCase(mod.asText())) return true;
        }
        return false;
    }

    private String extractPackageName(String qualifiedName) {
        if (qualifiedName == null) return null;
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : null;
    }

    private String extractQualifiedName(String symbol) {
        if (symbol == null) return "";
        int colon = symbol.indexOf(':');
        return colon >= 0 ? symbol.substring(colon + 1) : symbol;
    }

    /**
     * extension point 결과의 owner_type_fqn 앵커를 계산한다.
     * - TYPE 중심 결과에서는 자기 자신의 qualifiedName을 owner로 사용한다.
     */
    private String resolveOwnerTypeFqn(SymbolEntity symbol) {
        return symbol.getQualifiedName();
    }

    /**
     * 파일 트리 문서 생성을 위해 타입의 소스 파일 경로를 전달한다.
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
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private boolean isContractTestName(String simpleName) {
        if (simpleName == null) return false;
        String lower = simpleName.toLowerCase(Locale.ROOT);
        return (lower.startsWith("abstract") && lower.endsWith("test"))
                || lower.endsWith("contracttest");
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
            Map<String, List<SymbolEntity>> methodsByOwner,
            Map<String, String>             methodOwnerIndex
    ) {}

    private record FactsSignals(
            Set<String> spiQualifiedNames,
            Set<String> moduleUsedQualifiedNames,
            Set<String> contractTestSimpleNames
    ) {}

    private record SubsystemMaps(
            Map<String, String> memberToSubsystem,
            Map<String, String> labelBySubsystem
    ) {}

    private record PhaseResult(
            String confidence,
            List<String> signals,
            int score
    ) {}
}
