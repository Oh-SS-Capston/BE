package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.support.EdgeWeightPolicy;
import com.example.ossdoc.domain.cluster.support.signal.SemanticSignalAugmenter;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GraphProjectionService {

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final SymbolEvidenceRepository symbolEvidenceRepository;
    private final EdgeWeightPolicy edgeWeightPolicy;
    private final SemanticSignalAugmenter semanticSignalAugmenter;

    // refactplan.md 4순위: Javadoc 토큰화 시 제거할 영어 불용어 + Javadoc 지시어.
    private static final Set<String> DOC_STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of",
            "with", "by", "from", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall", "should",
            "may", "might", "must", "can", "could", "this", "that", "these", "those",
            "it", "its", "they", "them", "their", "we", "our", "you", "your", "he",
            "she", "his", "her", "not", "no", "nor", "if", "as", "when", "where",
            "which", "who", "what", "how", "all", "any", "both", "each", "few", "more",
            "most", "other", "some", "such", "only", "than", "then", "so", "too", "very",
            "just", "also", "into", "out", "off", "over", "under", "again", "once",
            "param", "return", "returns", "throws", "see", "since", "deprecated",
            "author", "version", "note", "true", "false", "null"
    );

    /**
     * graphstore와 public_api_entry를 합쳐 군집화용 투영 그래프를 구성한다.
     *
     * @param refinedEntrySymbolIds EntryPointDetectService 기반 정제 진입점 symbolId 집합.
     *                              빈 set이면 모든 노드의 entryPoint = false.
     */
    public ProjectedGraph loadProjectedGraph(String runId, Set<String> refinedEntrySymbolIds) {
        List<SymbolEntity> typeSymbols;
        try {
            // 노드 인덱스를 매 실행 동일하게 만들기 위해 심볼 ID 기준 정렬 조회를 사용한다.
            typeSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKindOrderBySymbolIdAsc(runId, SymbolKind.TYPE);
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        if (typeSymbols == null || typeSymbols.isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        int totalLoaded = typeSymbols.size();
        typeSymbols = typeSymbols.stream()
                .filter(s -> !isTestSourceRoot(s.getSourceRoot()))
                .toList();
        int excluded = totalLoaded - typeSymbols.size();
        if (excluded > 0) {
            log.info("[CLUSTER] Excluded {} test-source symbols from projection. runId={}, remaining={}",
                    excluded, runId, typeSymbols.size());
        }

        if (typeSymbols.isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        Map<String, Integer> evidenceCountBySymbolId;
        try {
            evidenceCountBySymbolId = symbolEvidenceRepository.countBySymbolIdForRun(runId).stream()
                    .collect(Collectors.toMap(
                            row -> (String) row[0],
                            row -> ((Number) row[1]).intValue()
                    ));
        } catch (Exception e) {
            evidenceCountBySymbolId = Map.of();
        }

        List<ProjectedNode> nodes = new ArrayList<>();
        Map<String, Integer> nodeIndexMap = new HashMap<>();

        for (int i = 0; i < typeSymbols.size(); i++) {
            SymbolEntity symbol = typeSymbols.get(i);
            String packageName = extractPackageName(symbol.getQualifiedName());

            nodes.add(ProjectedNode.builder()
                    .symbolId(symbol.getSymbolId())
                    .qualifiedName(symbol.getQualifiedName())
                    .simpleName(symbol.getSimpleName())
                    .packageName(packageName)
                    .moduleId(symbol.getModule() == null ? null : symbol.getModule().getModuleId())
                    .entryPoint(refinedEntrySymbolIds.contains(symbol.getSymbolId()))
                    // rankings.json에서 "설명 가능한 위치"를 만들기 위한 메타데이터를 함께 투영한다.
                    .symbolKind(symbol.getSymbolKind() == null ? null : symbol.getSymbolKind().name())
                    .ownerSymbol(symbol.getOwner() == null ? null : symbol.getOwner().getQualifiedName())
                    .sourceFile(symbol.getSourceFile() == null ? null : symbol.getSourceFile().getPath())
                    .sourceStartLine(symbol.getSourceStartLine())
                    .sourceEndLine(symbol.getSourceEndLine())
                    .evidenceCount(evidenceCountBySymbolId.getOrDefault(symbol.getSymbolId(), 0))
                    // refactplan.md 4순위: Javadoc 본문을 소문자 토큰 리스트로 사전 투영한다.
                    .docTokens(tokenizeDocComment(symbol.getDocComment()))
                    .build());

            nodeIndexMap.put(symbol.getSymbolId(), i);
        }

        List<Edge> allEdges;
        try {
            allEdges = edgeRepository.findAllByRun_RunId(runId);
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        // 엣지 집계 순서를 고정해 부동소수 연산 누적 순서 차이로 인한 미세 흔들림을 줄인다.
        // 원본 컬렉션을 직접 변경하지 않도록 복사본을 정렬한다.
        List<Edge> sortedEdges = new ArrayList<>(allEdges);
        sortedEdges.sort(Comparator
                .comparing((Edge edge) -> safeSymbolId(edge.getFromSymbol()))
                .thenComparing(edge -> safeSymbolId(edge.getToSymbol()))
                .thenComparing(edge -> edge.getEdgeType() == null ? "" : edge.getEdgeType().name())
                .thenComparing(edge -> edge.getEdgeId() == null ? Long.MAX_VALUE : edge.getEdgeId()));

        // key 정렬이 보장되는 맵을 사용해 projected edge 리스트 순서도 재현 가능하게 맞춘다.
        Map<String, Double> undirectedWeightMap = new TreeMap<>();

        for (Edge edge : sortedEdges) {
            if (edge.getFromSymbol() == null || edge.getToSymbol() == null) {
                continue;
            }

            Integer fromIndex = nodeIndexMap.get(edge.getFromSymbol().getSymbolId());
            Integer toIndex = nodeIndexMap.get(edge.getToSymbol().getSymbolId());

            if (fromIndex == null || toIndex == null || fromIndex.equals(toIndex)) {
                continue;
            }

            int a = Math.min(fromIndex, toIndex);
            int b = Math.max(fromIndex, toIndex);

            String key = a + ":" + b;
            double weight = edgeWeightPolicy.weightOf(edge);

            undirectedWeightMap.merge(key, weight, Double::sum);
        }

        // refactplan.md 2~5순위: 의미 신호 가상 엣지를 코드 엣지와 동일한 weight map 에 합산한다.
        // EdgeWeightPolicy·graphstore DB 는 손대지 않고 메모리 상에서만 보강한다.
        Map<String, Object> signalMeta;
        try {
            signalMeta = semanticSignalAugmenter.augment(nodes, nodeIndexMap, undirectedWeightMap);
        } catch (Exception e) {
            // 신호 보강 실패가 군집화 자체를 막지 않도록 코드 엣지만으로 진행한다.
            log.error("[CLUSTER] semantic signal augmentation failed. proceeding with code edges only. runId={}",
                    runId, e);
            signalMeta = Map.of("error", e.getClass().getSimpleName());
        }

        List<ProjectedEdge> projectedEdges = new ArrayList<>();
        for (Map.Entry<String, Double> entry : undirectedWeightMap.entrySet()) {
            String[] split = entry.getKey().split(":");
            projectedEdges.add(new ProjectedEdge(
                    Integer.parseInt(split[0]),
                    Integer.parseInt(split[1]),
                    entry.getValue()
            ));
        }

        return ProjectedGraph.builder()
                .runId(runId)
                .nodes(nodes)
                .edges(projectedEdges)
                .nodeIndexMap(nodeIndexMap)
                .signalMeta(signalMeta)
                .build();
    }

    private String extractPackageName(String qualifiedName) {
        if (qualifiedName == null) return "";
        // "type:", "method:", "field:" 등 스킴 접두사 제거
        int colonIdx = qualifiedName.indexOf(':');
        String fqn = colonIdx >= 0 ? qualifiedName.substring(colonIdx + 1) : qualifiedName;
        // Java 패키지 세그먼트는 소문자 시작 — 첫 대문자 세그먼트(클래스명)에서 멈춘다
        String[] parts = fqn.split("\\.");
        StringBuilder pkg = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || Character.isUpperCase(part.charAt(0))) break;
            if (pkg.length() > 0) pkg.append('.');
            pkg.append(part);
        }
        return pkg.toString();
    }

    /**
     * Maven/Gradle 표준 test source root 패턴을 판별한다.
     * 예) src/test/java, src/test/kotlin, test, test/java
     */
    private boolean isTestSourceRoot(String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            return false;
        }
        String normalized = sourceRoot.replace('\\', '/');
        return normalized.contains("/test/")
                || normalized.startsWith("test/")
                || normalized.equals("test");
    }

    /**
     * 정렬 비교 시 null-safe 처리를 위해 심볼 ID를 문자열로 변환한다.
     */
    private String safeSymbolId(SymbolEntity symbol) {
        if (symbol == null || symbol.getSymbolId() == null) {
            return "";
        }
        return symbol.getSymbolId();
    }

    /**
     * Javadoc 본문을 소문자 토큰 리스트로 변환한다. (refactplan.md 4순위 DocCommentSignalProvider 용)
     * HTML 태그·Javadoc 지시어 제거 → 영어 불용어·단자(3자 미만) 필터링.
     * 중복 토큰을 보존해 TF-IDF 계산 시 term frequency를 정확히 반영한다.
     */
    private List<String> tokenizeDocComment(String docComment) {
        if (docComment == null || docComment.isBlank()) {
            return List.of();
        }
        String text = docComment
                .replaceAll("<[^>]+>", " ")
                .replaceAll("@\\w+", " ")
                .replaceAll("[{}]", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[^a-zA-Z]+")) {
            if (token.length() < 3) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            if (!DOC_STOPWORDS.contains(lower)) {
                tokens.add(lower);
            }
        }
        return List.copyOf(tokens);
    }
}
