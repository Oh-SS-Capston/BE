package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.support.ScoreNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SymbolScoringService {
    private static final String UNASSIGNED_SUBSYSTEM_ID = "ss_unassigned";

    /* 역할
    * - degree map 계산
    * - bridge map 계산
    * - symbol별 점수 계산
    * - 전체 symbol ranking item 리스트 반환
    *  */

    private final ScoreNormalizer scoreNormalizer;

    public List<SymbolRankingItem> scoreSymbols(ProjectedGraph graph, Map<String, String> subsystemBySymbolId) {
        Map<String, Double> degreeMap = buildDegreeMap(graph);
        Map<String, Double> bridgeMap = buildBridgeMap(graph, subsystemBySymbolId);

        double maxDegree = degreeMap.values().stream().mapToDouble(v -> v).max().orElse(1.0);
        double maxBridge = bridgeMap.values().stream().mapToDouble(v -> v).max().orElse(1.0);

        List<SymbolRankingItem> items = new ArrayList<>();

        for (ProjectedNode node : graph.getNodes()) {
            double structural = scoreNormalizer.normalize(
                    degreeMap.getOrDefault(node.getSymbolId(), 0.0),
                    maxDegree
            );
            double bridge = scoreNormalizer.normalize(
                    bridgeMap.getOrDefault(node.getSymbolId(), 0.0),
                    maxBridge
            );
            double api = node.isPublicApi() ? 1.0 : 0.0;
            double evidence = 0.5;
            double subsystemCentrality = structural;

            double total = 0.35 * structural
                    + 0.25 * bridge
                    + 0.20 * api
                    + 0.10 * evidence
                    + 0.10 * subsystemCentrality;

            items.add(SymbolRankingItem.builder()
                    .symbolId(node.getSymbolId())
                    .qualifiedName(node.getQualifiedName())
                    .subsystemId(resolveSubsystemId(node.getSymbolId(), subsystemBySymbolId))
                    .score(total)
                    .structuralScore(structural)
                    .bridgeScore(bridge)
                    .apiScore(api)
                    .evidenceScore(evidence)
                    .subsystemCentralityScore(subsystemCentrality)
                    .build());
        }

        return items;
    }

    public List<SymbolRankingItem> topRankedSymbols(List<SymbolRankingItem> allItems, int topK) {
        List<SymbolRankingItem> sorted = allItems.stream()
                .sorted(Comparator.comparingDouble(SymbolRankingItem::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toCollection(ArrayList::new));

        List<SymbolRankingItem> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            SymbolRankingItem item = sorted.get(i);
            ranked.add(SymbolRankingItem.builder()
                    .rank(i + 1)
                    .symbolId(item.getSymbolId())
                    .qualifiedName(item.getQualifiedName())
                    .subsystemId(item.getSubsystemId())
                    .score(item.getScore())
                    .structuralScore(item.getStructuralScore())
                    .bridgeScore(item.getBridgeScore())
                    .apiScore(item.getApiScore())
                    .evidenceScore(item.getEvidenceScore())
                    .subsystemCentralityScore(item.getSubsystemCentralityScore())
                    .build());
        }
        return ranked;
    }

    /**
     * 심볼이 subsystem 조립 결과에서 누락된 경우에도 null 대신 안전한 기본값을 부여한다.
     * - 이유: null subsystemId는 이후 groupingBy 단계에서 예외를 유발할 수 있다.
     */
    private String resolveSubsystemId(String symbolId, Map<String, String> subsystemBySymbolId) {
        String subsystemId = subsystemBySymbolId.get(symbolId);
        if (subsystemId == null || subsystemId.isBlank()) {
            return UNASSIGNED_SUBSYSTEM_ID;
        }
        return subsystemId;
    }

    private Map<String, Double> buildDegreeMap(ProjectedGraph graph) {
        Map<String, Double> degreeMap = new HashMap<>();

        for (ProjectedNode node : graph.getNodes()) {
            degreeMap.put(node.getSymbolId(), 0.0);
        }

        for (ProjectedEdge edge : graph.getEdges()) {
            ProjectedNode from = graph.getNodes().get(edge.getFromIndex());
            ProjectedNode to = graph.getNodes().get(edge.getToIndex());

            degreeMap.merge(from.getSymbolId(), edge.getWeight(), Double::sum);
            degreeMap.merge(to.getSymbolId(), edge.getWeight(), Double::sum);
        }

        return degreeMap;
    }

    private Map<String, Double> buildBridgeMap(ProjectedGraph graph, Map<String, String> subsystemBySymbolId) {
        Map<String, Double> bridgeMap = new HashMap<>();

        for (ProjectedNode node : graph.getNodes()) {
            bridgeMap.put(node.getSymbolId(), 0.0);
        }

        for (ProjectedEdge edge : graph.getEdges()) {
            ProjectedNode from = graph.getNodes().get(edge.getFromIndex());
            ProjectedNode to = graph.getNodes().get(edge.getToIndex());

            String fromSubsystem = subsystemBySymbolId.get(from.getSymbolId());
            String toSubsystem = subsystemBySymbolId.get(to.getSymbolId());

            if (fromSubsystem != null && toSubsystem != null && !fromSubsystem.equals(toSubsystem)) {
                bridgeMap.merge(from.getSymbolId(), edge.getWeight(), Double::sum);
                bridgeMap.merge(to.getSymbolId(), edge.getWeight(), Double::sum);
            }
        }

        return bridgeMap;
    }
}
