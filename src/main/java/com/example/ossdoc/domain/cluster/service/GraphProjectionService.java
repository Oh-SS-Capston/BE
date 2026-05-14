package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.support.EdgeWeightPolicy;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GraphProjectionService {

    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;
    private final PublicApiEntryRepository publicApiEntryRepository;
    private final EdgeWeightPolicy edgeWeightPolicy;

    /**
     * graphstore와 public_api_entry를 합쳐 군집화용 투영 그래프를 구성한다.
     */
    public ProjectedGraph loadProjectedGraph(String runId) {
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

        Set<String> publicApiSymbolIds;
        try {
            publicApiSymbolIds = publicApiEntryRepository.findAllByRun_RunId(runId).stream()
                    .map(PublicApiEntry::getSymbol)
                    .map(SymbolEntity::getSymbolId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
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
                    .publicApi(publicApiSymbolIds.contains(symbol.getSymbolId()))
                    // rankings.json에서 "설명 가능한 위치"를 만들기 위한 메타데이터를 함께 투영한다.
                    .symbolKind(symbol.getSymbolKind() == null ? null : symbol.getSymbolKind().name())
                    .ownerSymbol(symbol.getOwner() == null ? null : symbol.getOwner().getQualifiedName())
                    .sourceFile(symbol.getSourceFile() == null ? null : symbol.getSourceFile().getPath())
                    .sourceStartLine(symbol.getSourceStartLine())
                    .sourceEndLine(symbol.getSourceEndLine())
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
                .build();
    }

    private String extractPackageName(String qualifiedName) {
        int idx = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
        if (idx < 0) return "";
        return qualifiedName.substring(0, idx);
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
}
