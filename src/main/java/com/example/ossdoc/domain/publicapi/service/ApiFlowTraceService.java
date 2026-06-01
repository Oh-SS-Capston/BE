package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.cluster.support.supercluster.ModuleResolver;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.artifact.ApiFlowTraceJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * API 진입점별 BFS 호출 경로 추적 서비스.
 *
 * <p>refactplan.md §273-301 (M4) 결정 구체화 + Second_Clustering_Plan.md Phase 4-B.
 * {@link com.example.ossdoc.domain.cluster.support.signal.PublicApiFlowSignalProvider}의
 * BFS 핵심 로직을 LLM 입력 목적으로 이전한 서비스다.
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
    private static final int MAX_ENTRY_POINTS = 50;
    private static final String ARTIFACT_SCHEMA_VERSION = "1.0";
    private static final String ARTIFACT_PATH = "publicapi/api_flow_trace.json";

    private final RepoRunRepository repoRunRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    private record ApiMapEntry(String symbolId, String role) {}

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

        // CALLS 엣지 로드 + 인접 리스트 구성
        List<Edge> callEdges = edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(
                runId, List.of(EdgeType.CALLS));
        Map<String, List<AdjacentEdge>> adj = buildAdjacency(callEdges);

        // 진입점 목록 (심볼 인덱스에 존재하는 것만, 최대 MAX_ENTRY_POINTS)
        List<ApiMapEntry> filteredEntries = apiMapEntries.stream()
                .filter(e -> symbolIndex.containsKey(e.symbolId()))
                .limit(MAX_ENTRY_POINTS)
                .toList();

        ModuleResolver moduleResolver = loadModuleResolver(runId);
        Map<String, String> typeSourceRootIndex = buildTypeSourceRootIndex(symbolIndex);

        int totalSymbols = symbolIndex.size();
        List<ApiFlowTraceJson.EntryPointTrace> traces = new ArrayList<>();

        for (ApiMapEntry entry : filteredEntries) {
            String entrySymbolId = entry.symbolId();
            SymbolEntity entrySym = symbolIndex.get(entrySymbolId);
            ApiFlowTraceJson.EntryPointTrace trace = bfsTrace(
                    entrySymbolId, entrySym, entry.role(),
                    adj, symbolIndex, callEdges, totalSymbols,
                    moduleResolver, typeSourceRootIndex
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
                            if (!symbolId.isBlank()) {
                                entries.add(new ApiMapEntry(symbolId, role));
                            }
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
            Map<String, List<AdjacentEdge>> adj,
            Map<String, SymbolEntity> symbolIndex,
            List<Edge> callEdges,
            int totalSymbols,
            ModuleResolver moduleResolver,
            Map<String, String> typeSourceRootIndex
    ) {
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        depthMap.put(entrySymbolId, 0);

        Deque<String> queue = new ArrayDeque<>();
        int maxActualDepth = 0;

        // API_MAP_JSON entry_points는 TYPE 레벨(symbol_id="type:org.foo.Bar")이다.
        // 진입 TYPE 심볼을 시작점으로 CALLS 엣지를 BFS 탐색한다.
        // ⚠️ 결과 trace의 entryQualifiedName도 TYPE FQN(예: "org.foo.Bar")이므로
        //    METHOD 단위(apiEntries/coreMethods.fqn)와 직접 매칭되지 않는다.
        //    LLM enrich 단계에서 메서드 fqn→소유 TYPE fqn 변환으로 연결해야 한다.
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
                        .confidence(e.getConfidence() == null ? 0.0 : e.getConfidence().doubleValue())
                        .evidence(e.getOrigin() == null ? "AST" : e.getOrigin().name())
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
