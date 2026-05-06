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

    public ProjectedGraph loadProjectedGraph(String runId) {
        List<SymbolEntity> typeSymbols;
        try {
            typeSymbols = symbolRepository.findAllByRunIdAndSymbolKind(runId, SymbolKind.TYPE);
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        if (typeSymbols == null || typeSymbols.isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        Set<String> publicApiSymbolIds;
        try {
            publicApiSymbolIds = publicApiEntryRepository.findAllByRunId(runId).stream()
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
                    .build());

            nodeIndexMap.put(symbol.getSymbolId(), i);
        }

        List<Edge> allEdges;
        try {
            allEdges = edgeRepository.findAllByRunRunId(runId);
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        Map<String, Double> undirectedWeightMap = new HashMap<>();

        for (Edge edge : allEdges) {
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
}