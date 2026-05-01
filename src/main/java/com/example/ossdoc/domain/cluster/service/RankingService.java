package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.*;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.model.ranking.*;


import com.example.ossdoc.domain.cluster.support.ScoreNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RankingService {

    private final ScoreNormalizer scoreNormalizer;

    public RankingResult rank(ProjectedGraph graph, List<Subsystem> subsystems, int topK) {
        Map<String, String> subsystemBySymbolId = new HashMap<>();
        for (Subsystem subsystem : subsystems) {
            for (String member : subsystem.getMemberSymbolIds()) {
                subsystemBySymbolId.put(member, subsystem.getSubsystemId());
            }
        }

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

        double maxDegree = degreeMap.values().stream().mapToDouble(v -> v).max().orElse(1.0);

        List<SymbolRankingItem> symbolItems = new ArrayList<>();
        for (ProjectedNode node : graph.getNodes()) {
            double structural = scoreNormalizer.normalize(degreeMap.get(node.getSymbolId()), maxDegree);
            double bridge = 0.0; // 추후 subsystem 간 연결도 계산으로 확장
            double api = node.isPublicApi() ? 1.0 : 0.0;
            double evidence = 0.5; // 추후 evidence 테이블 기반 정교화
            double centrality = structural;

            double total = 0.35 * structural
                    + 0.25 * bridge
                    + 0.20 * api
                    + 0.10 * evidence
                    + 0.10 * centrality;

            symbolItems.add(SymbolRankingItem.builder()
                    .symbolId(node.getSymbolId())
                    .qualifiedName(node.getQualifiedName())
                    .subsystemId(subsystemBySymbolId.get(node.getSymbolId()))
                    .score(total)
                    .structuralScore(structural)
                    .bridgeScore(bridge)
                    .apiScore(api)
                    .evidenceScore(evidence)
                    .subsystemCentralityScore(centrality)
                    .build());
        }

        symbolItems = symbolItems.stream()
                .sorted(Comparator.comparingDouble(SymbolRankingItem::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        for (int i = 0; i < symbolItems.size(); i++) {
            SymbolRankingItem item = symbolItems.get(i);
            symbolItems.set(i, SymbolRankingItem.builder()
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

        Map<String, Double> subsystemScoreMap = new HashMap<>();
        for (Subsystem subsystem : subsystems) {
            double score = symbolItems.stream()
                    .filter(item -> subsystem.getSubsystemId().equals(item.getSubsystemId()))
                    .mapToDouble(SymbolRankingItem::getScore)
                    .sum();
            subsystemScoreMap.put(subsystem.getSubsystemId(), score);
        }

        List<SubsystemRankingItem> subsystemItems = subsystems.stream()
                .map(ss -> new SubsystemRankingItem(
                        0,
                        ss.getSubsystemId(),
                        ss.getName(),
                        subsystemScoreMap.getOrDefault(ss.getSubsystemId(), 0.0)
                ))
                .sorted(Comparator.comparingDouble(SubsystemRankingItem::getScore).reversed())
                .collect(Collectors.toList());

        List<SubsystemRankingItem> rankedSubsystemItems = new ArrayList<>();
        for (int i = 0; i < subsystemItems.size(); i++) {
            SubsystemRankingItem item = subsystemItems.get(i);
            rankedSubsystemItems.add(new SubsystemRankingItem(
                    i + 1,
                    item.getSubsystemId(),
                    item.getName(),
                    item.getScore()
            ));
        }

        return new RankingResult(symbolItems, rankedSubsystemItems);
    }

    public record RankingResult(
            List<SymbolRankingItem> symbolRankings,
            List<SubsystemRankingItem> subsystemRankings
    ) {}
}