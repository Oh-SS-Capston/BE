package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.cluster.support.supercluster.ModuleResolver;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.publicapi.artifact.ApiFlowTraceJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * API 진입점별 BFS 호출 경로 추적 서비스.
 *
 * <p>refactplan.md §273-301 (M4) 결정 구체화 + Second_Clustering_Plan.md Phase 4-B.
 * cluster 의 BFS 신호 provider 핵심 로직을 LLM 입력 목적으로 이전한 서비스다.
 * (원본 {@code PublicApiFlowSignalProvider} 는 Phase 4-B 에서 제거됨)
 *
 * <ul>
 *   <li>cluster 가상 엣지가 아닌 LLM 시나리오/API 문서의 호출 흐름 근거를 생성한다.</li>
 *   <li>CALLS 엣지 기반 BFS로 진입점에서 도달 가능한 메서드/타입 서브그래프를 추출한다.</li>
 *   <li>결과는 {@code API_FLOW_TRACE_JSON} artifact로 저장된다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiFlowTraceService {

    /** flow set이 전체 노드 수의 이 비율을 초과하면 해당 진입점을 잘린 것(truncated)으로 표시한다. */
    private static final double FLOW_SET_OVERLOAD_RATIO = 0.80;
    private static final int DEFAULT_MAX_BFS_DEPTH = 4;
    // P0-2: TYPE 진입점을 member METHOD 시드로 확장할 때 타입당 상한
    private static final int MAX_TYPE_SEED_METHODS = 10;

    /** 트레이스 대상 진입점 상한. 환경변수/properties로 프로젝트 규모별 조정 가능(#8). */
    @Value("${ossdoc.api-flow.max-entry-points:50}")
    private int maxEntryPoints;
    private static final String ARTIFACT_SCHEMA_VERSION = "1.1";
    private static final String ARTIFACT_PATH = "publicapi/api_flow_trace.json";

    private final RepoRunRepository repoRunRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final EdgeInferencePolicy edgeInferencePolicy;

    private record ApiMapEntry(String symbolId, String role, String confidence, List<String> entryMethodIds) {}

    private static int confidenceRank(String confidence) {
        if (confidence == null) return 0;
        return switch (confidence.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 3;
            case "MED"  -> 2;
            case "LOW"  -> 1;
            default     -> 0;
        };
    }

    /**
     * runId 의 진입점 목록을 기반으로 BFS 호출 경로 추적을 실행하고 artifact 로 저장한다.
     *
     * @return 저장된 artifact
     */
    @Transactional
    public Artifact trace(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));

        // API_MAP_JSON의 entry_points를 진입점 소스로 사용 (PR A-1)
        List<ApiMapEntry> apiMapEntries = loadApiMapEntries(runId);
        if (apiMapEntries.isEmpty()) {
            log.warn("[API-FLOW-TRACE] API_MAP_JSON entry_points 없음. BFS 시작점 0건. runId={}", runId);
        }

        // 심볼 인덱스 구성 (METHOD + TYPE)
        List<SymbolEntity> methods = symbolRepository.findAllByRun_RunIdAndSymbolKind(runId, SymbolKind.METHOD);
        List<SymbolEntity> types = symbolRepository.findAllByRun_RunIdAndSymbolKind(runId, SymbolKind.TYPE);
        Map<String, SymbolEntity> symbolIndex = new HashMap<>(methods.size() + types.size());
        for (SymbolEntity s : types) symbolIndex.put(s.getSymbolId(), s);
        for (SymbolEntity s : methods) symbolIndex.put(s.getSymbolId(), s);

        // P0-2: TYPE 진입점 → member METHOD 시드 확장을 위한 소유 관계 인덱스
        Map<String, List<SymbolEntity>> typeToMethods = buildTypeToMethodsIndex(methods);

        // CALLS 엣지 로드 + 인접 리스트 구성
        List<Edge> callEdges = edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(
                        runId, List.of(EdgeType.CALLS))
                .stream()
                .filter(edgeInferencePolicy::isUsableForInference)
                .toList();
        Map<String, List<AdjacentEdge>> adj = buildAdjacency(callEdges);

        // 진입점 목록: confidence(HIGH→MED→LOW) → role(PRIMARY 우선) 정렬 후 cap 적용 (#8)
        List<ApiMapEntry> eligible = apiMapEntries.stream()
                .filter(e -> symbolIndex.containsKey(e.symbolId()))
                .sorted(Comparator
                        .comparingInt((ApiMapEntry e) -> confidenceRank(e.confidence())).reversed()
                        .thenComparingInt(e -> "PRIMARY".equals(e.role()) ? 0 : 1))
                .toList();
        int truncatedCount = Math.max(0, eligible.size() - maxEntryPoints);
        List<ApiMapEntry> filteredEntries = eligible.stream().limit(maxEntryPoints).toList();

        ModuleResolver moduleResolver = loadModuleResolver(runId);
        Map<String, String> typeSourceRootIndex = buildTypeSourceRootIndex(symbolIndex);

        int totalSymbols = symbolIndex.size();
        List<ApiFlowTraceJson.EntryPointTrace> traces = new ArrayList<>();

        for (ApiMapEntry entry : filteredEntries) {
            String entrySymbolId = entry.symbolId();
            SymbolEntity entrySym = symbolIndex.get(entrySymbolId);
            ApiFlowTraceJson.EntryPointTrace trace = bfsTrace(
                    entrySymbolId, entrySym, entry.role(), entry.entryMethodIds(),
                    adj, symbolIndex, callEdges, totalSymbols,
                    moduleResolver, typeSourceRootIndex, typeToMethods
            );
            traces.add(trace);
        }

        ApiFlowTraceJson json = ApiFlowTraceJson.builder()
                .schemaVersion(ARTIFACT_SCHEMA_VERSION)
                .runId(runId)
                .generatedAt(OffsetDateTime.now())
                .meta(ApiFlowTraceJson.TraceMeta.builder()
                        .entryPointCount(apiMapEntries.size())
                        .tracedCount(traces.size())
                        .maxBfsDepth(DEFAULT_MAX_BFS_DEPTH)
                        .flowSetOverloadRatio(FLOW_SET_OVERLOAD_RATIO)
                        .truncatedCount(truncatedCount)
                        .build())
                .traces(traces)
                .build();

        var jsonNode = objectMapper.valueToTree(json);
        Artifact artifact = artifactService.saveJsonArtifact(
                run, ArtifactKind.API_FLOW_TRACE_JSON, ARTIFACT_SCHEMA_VERSION, ARTIFACT_PATH, jsonNode
        );

        log.info("[API-FLOW-TRACE] trace complete. runId={}, apiMapEntries={}, traced={}",
                runId, apiMapEntries.size(), traces.size());
        return artifact;
    }

    private List<ApiMapEntry> loadApiMapEntries(String runId) {
        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.API_MAP_JSON)
                .map(artifact -> {
                    try {
                        JsonNode root = artifact.getMeta();
                        JsonNode entryPoints = root.path("entry_points");
                        if (!entryPoints.isArray()) {
                            log.warn("[API-FLOW-TRACE] API_MAP_JSON에 entry_points 배열 없음. runId={}", runId);
                            return List.<ApiMapEntry>of();
                        }
                        List<ApiMapEntry> entries = new ArrayList<>();
                        for (JsonNode ep : entryPoints) {
                            String symbolId = ep.path("symbol_id").asText("");
                            String role = ep.path("role").asText("PRIMARY");
                            String confidence = ep.path("confidence").asText("MED");
                            if (symbolId.isBlank()) continue;
                            List<String> methodIds = new ArrayList<>();
                            JsonNode emArray = ep.path("entry_methods");
                            if (emArray.isArray()) {
                                for (JsonNode em : emArray) {
                                    String emId = em.path("symbol_id").asText("");
                                    if (!emId.isBlank()) methodIds.add(emId);
                                }
                            }
                            entries.add(new ApiMapEntry(symbolId, role, confidence, List.copyOf(methodIds)));
                        }
                        return entries;
                    } catch (Exception e) {
                        log.warn("[API-FLOW-TRACE] API_MAP_JSON 파싱 실패. runId={}", runId, e);
                        return List.<ApiMapEntry>of();
                    }
                })
                .orElseGet(() -> {
                    log.warn("[API-FLOW-TRACE] API_MAP_JSON artifact 없음. runId={}", runId);
                    return List.of();
                });
    }

    /**
     * 최신 API_FLOW_TRACE_JSON artifact 가 이미 존재하면 반환하고, 없으면 새로 실행한다.
     */
    @Transactional
    public Artifact traceOrGet(String runId) {
        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.API_FLOW_TRACE_JSON)
                .orElseGet(() -> trace(runId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // BFS 핵심 로직
    // ──────────────────────────────────────────────────────────────────────

    private ApiFlowTraceJson.EntryPointTrace bfsTrace(
            String entrySymbolId,
            SymbolEntity entrySym,
            String role,
            List<String> explicitEntryMethodIds,
            Map<String, List<AdjacentEdge>> adj,
            Map<String, SymbolEntity> symbolIndex,
            List<Edge> callEdges,
            int totalSymbols,
            ModuleResolver moduleResolver,
            Map<String, String> typeSourceRootIndex,
            Map<String, List<SymbolEntity>> typeToMethods
    ) {
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        depthMap.put(entrySymbolId, 0);

        Deque<String> queue = new ArrayDeque<>();
        int maxActualDepth = 0;

        // P0-2 / #2: TYPE 진입점을 member METHOD 시드로 확장해 CALLS BFS를 실질화한다.
        // #2: API_MAP_JSON에 entry_methods가 명시된 경우 우선 사용 — 탐지 시점의 근거 기반 시드.
        // 없으면 휴리스틱(prioritizeTypeMembers) fallback.
        if (entrySym != null && entrySym.getSymbolKind() == SymbolKind.TYPE) {
            List<String> validExplicit = explicitEntryMethodIds.stream()
                    .filter(symbolIndex::containsKey)
                    .toList();
            List<String> seeds = validExplicit.isEmpty()
                    ? prioritizeTypeMembers(typeToMethods.getOrDefault(entrySymbolId, List.of()), adj)
                    : validExplicit;
            for (String seedId : seeds) {
                if (!depthMap.containsKey(seedId)) {
                    depthMap.put(seedId, 0);
                    queue.add(seedId);
                }
            }
        }

        queue.add(entrySymbolId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int curDepth = depthMap.get(cur);
            if (curDepth >= DEFAULT_MAX_BFS_DEPTH) continue;

            for (AdjacentEdge neighbor : adj.getOrDefault(cur, List.of())) {
                if (!depthMap.containsKey(neighbor.toSymbolId)) {
                    int nextDepth = curDepth + 1;
                    depthMap.put(neighbor.toSymbolId, nextDepth);
                    maxActualDepth = Math.max(maxActualDepth, nextDepth);
                    queue.add(neighbor.toSymbolId);
                }
            }
        }

        boolean truncated = (double) depthMap.size() / Math.max(1, totalSymbols) > FLOW_SET_OVERLOAD_RATIO;

        // 도달 가능 노드 목록 (깊이 오름차순)
        List<ApiFlowTraceJson.FlowNode> nodes = depthMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(e -> {
                    SymbolEntity sym = symbolIndex.get(e.getKey());
                    return ApiFlowTraceJson.FlowNode.builder()
                            .symbolId(e.getKey())
                            .kind(sym == null ? "UNKNOWN" : sym.getSymbolKind().name())
                            .name(sym == null ? e.getKey() : simpleName(sym.getQualifiedName()))
                            .qualifiedName(sym == null ? e.getKey() : sym.getQualifiedName())
                            .moduleId(resolveModuleId(sym, moduleResolver, typeSourceRootIndex))
                            .bfsDepth(e.getValue())
                            .build();
                })
                .toList();

        // 도달 가능 노드 간 CALLS 엣지
        Set<String> reachable = depthMap.keySet();
        List<ApiFlowTraceJson.FlowEdge> edges = callEdges.stream()
                .filter(e -> e.getToSymbol() != null
                        && reachable.contains(e.getFromSymbol().getSymbolId())
                        && reachable.contains(e.getToSymbol().getSymbolId()))
                .map(e -> ApiFlowTraceJson.FlowEdge.builder()
                        .fromSymbolId(e.getFromSymbol().getSymbolId())
                        .toSymbolId(e.getToSymbol().getSymbolId())
                        .edgeKind(EdgeType.CALLS.name())
                        .confidence(edgeInferencePolicy.effectiveConfidence(e, java.math.BigDecimal.ONE).doubleValue())
                        // evidence 필드는 1.0 호환을 위해 origin alias로 유지한다.
                        .evidence(e.getOrigin() == null ? "AST" : e.getOrigin().name())
                        .origin(e.getOrigin() == null ? null : e.getOrigin().name())
                        .derivationKind(e.getDerivationKind() == null ? null : e.getDerivationKind().name())
                        .resolution(e.getResolution() == null ? null : e.getResolution().name())
                        .resolutionReason(e.getResolutionReason())
                        .callSiteLine(e.getCallSiteLine())
                        .defaultVisible(edgeInferencePolicy.defaultVisible(e))
                        .attrs(e.getAttrs())
                        .build())
                .toList();

        return ApiFlowTraceJson.EntryPointTrace.builder()
                .entrySymbolId(entrySymbolId)
                .entryName(simpleName(entrySym.getQualifiedName()))
                .entryQualifiedName(entrySym.getQualifiedName())
                .exposure(role)
                .reachableNodes(nodes)
                .reachableEdges(edges)
                .maxDepth(maxActualDepth)
                .truncated(truncated)
                .build();
    }

    /**
     * BUILD_MANIFEST에서 sourceRoot↔moduleKey 매핑을 메모리로 구성한다.
     * artifact 부재·역직렬화 실패 시 빈 resolver → moduleId=null 유지(graceful degrade).
     */
    private ModuleResolver loadModuleResolver(String runId) {
        try {
            return artifactRepository
                    .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST)
                    .map(a -> {
                        try {
                            BuildManifest manifest = objectMapper.treeToValue(a.getMeta(), BuildManifest.class);
                            return ModuleResolver.from(manifest == null ? List.of() : manifest.getModules());
                        } catch (Exception e) {
                            log.warn("[API-FLOW-TRACE] BUILD_MANIFEST 역직렬화 실패. moduleId=null 유지. runId={}", runId, e);
                            return ModuleResolver.empty();
                        }
                    })
                    .orElseGet(() -> {
                        log.debug("[API-FLOW-TRACE] BUILD_MANIFEST 없음. moduleId=null 유지. runId={}", runId);
                        return ModuleResolver.empty();
                    });
        } catch (Exception e) {
            log.warn("[API-FLOW-TRACE] BUILD_MANIFEST 로드 실패. moduleId=null 유지. runId={}", runId, e);
            return ModuleResolver.empty();
        }
    }

    /**
     * TYPE 심볼의 정규화된 FQN → sourceRoot 인덱스.
     * METHOD 심볼의 sourceRoot가 null일 때 소유 클래스 경로로 fallback하기 위한 보조 구조다.
     */
    private Map<String, String> buildTypeSourceRootIndex(Map<String, SymbolEntity> symbolIndex) {
        Map<String, String> index = new HashMap<>();
        for (SymbolEntity sym : symbolIndex.values()) {
            if (sym.getSymbolKind() == SymbolKind.TYPE && sym.getSourceRoot() != null) {
                String fqn = stripKindPrefix(sym.getQualifiedName());
                if (!fqn.isBlank()) {
                    index.put(fqn, sym.getSourceRoot());
                }
            }
        }
        return index;
    }

    /**
     * 심볼의 sourceRoot로 moduleId를 결정한다.
     * METHOD 심볼의 sourceRoot가 null이면 qualifiedName에서 소유 클래스 FQN을 추출해 typeSourceRootIndex로 fallback한다.
     */
    private String resolveModuleId(SymbolEntity sym, ModuleResolver moduleResolver,
                                   Map<String, String> typeSourceRootIndex) {
        if (sym == null) return null;
        String sourceRoot = sym.getSourceRoot();
        if (sourceRoot == null && sym.getSymbolKind() == SymbolKind.METHOD) {
            // "method:org.foo.Bar#doSomething" → "org.foo.Bar" → typeSourceRootIndex 조회
            String classFqn = stripMethodPart(stripKindPrefix(sym.getQualifiedName()));
            sourceRoot = typeSourceRootIndex.get(classFqn);
        }
        return moduleResolver.resolveModuleKey(sourceRoot);
    }

    /** "type:org.foo.Bar" → "org.foo.Bar" (kind prefix 제거). */
    private static String stripKindPrefix(String qualifiedName) {
        if (qualifiedName == null) return "";
        int colonIdx = qualifiedName.indexOf(':');
        return colonIdx >= 0 ? qualifiedName.substring(colonIdx + 1) : qualifiedName;
    }

    /** "org.foo.Bar#doSomething" → "org.foo.Bar" (메서드 부분 제거). */
    private static String stripMethodPart(String fqn) {
        if (fqn == null) return "";
        int hashIdx = fqn.indexOf('#');
        return hashIdx >= 0 ? fqn.substring(0, hashIdx) : fqn;
    }

    // P0-2: TYPE → 소유 METHOD 인덱스 구성
    private Map<String, List<SymbolEntity>> buildTypeToMethodsIndex(List<SymbolEntity> methods) {
        Map<String, List<SymbolEntity>> index = new HashMap<>();
        for (SymbolEntity method : methods) {
            if (method.getOwner() != null) {
                index.computeIfAbsent(method.getOwner().getSymbolId(), k -> new ArrayList<>())
                        .add(method);
            }
        }
        return index;
    }

    /**
     * P0-2: TYPE member 메서드를 BFS 시드 우선순위로 정렬해 상위 MAX_TYPE_SEED_METHODS개를 반환한다.
     *
     * 우선순위: ① static(facade/factory 포함) → ② public 인스턴스 → CALLS 엣지 보유 우선
     * 이미 adj에 있는 메서드(실제 호출 관계 존재)를 앞으로 당긴다.
     */
    private List<String> prioritizeTypeMembers(
            List<SymbolEntity> members,
            Map<String, List<AdjacentEdge>> adj) {

        return members.stream()
                .filter(m -> m.getAccess() == AccessLevel.PUBLIC)
                .sorted(Comparator
                        .comparingInt(this::memberSeedPriority)
                        .thenComparingInt(m -> adj.containsKey(m.getSymbolId()) ? 0 : 1))
                .limit(MAX_TYPE_SEED_METHODS)
                .map(SymbolEntity::getSymbolId)
                .collect(Collectors.toList());
    }

    /** 낮을수록 우선: static → constructor → 일반 public */
    private int memberSeedPriority(SymbolEntity method) {
        JsonNode mods = method.getModifiers();
        if (mods != null && mods.isArray()) {
            for (JsonNode mod : mods) {
                if ("static".equalsIgnoreCase(mod.asText())) return 0;
            }
        }
        if (method.getSymbolKind() == SymbolKind.CONSTRUCTOR) return 1;
        return 2;
    }

    private Map<String, List<AdjacentEdge>> buildAdjacency(List<Edge> callEdges) {
        Map<String, List<AdjacentEdge>> adj = new HashMap<>();
        for (Edge edge : callEdges) {
            if (edge.getToSymbol() == null) continue;
            String from = edge.getFromSymbol().getSymbolId();
            String to = edge.getToSymbol().getSymbolId();
            adj.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new AdjacentEdge(to, edge));
        }
        return adj;
    }

    private static String simpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return "";
        int dot = qualifiedName.lastIndexOf('.');
        int hash = qualifiedName.lastIndexOf('#');
        int idx = Math.max(dot, hash);
        return idx >= 0 ? qualifiedName.substring(idx + 1) : qualifiedName;
    }

    private record AdjacentEdge(String toSymbolId, Edge edge) {}
}
