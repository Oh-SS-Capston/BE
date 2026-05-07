package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SubsystemScoringService {

    /**
     * subsystem별 심볼 점수를 집계해 score/core symbol을 채운다.
     * - subgroup 크기만으로 점수가 과도하게 커지는 문제를 줄이기 위해 크기 보정 점수를 사용한다.
     */
    public List<Subsystem> enrichSubsystems(List<Subsystem> subsystems, List<SymbolRankingItem> allSymbolItems) {
        // 방어 로직: subsystemId가 비어 있는 항목은 groupBy 이전에 제외해 NPE를 방지한다.
        Map<String, List<SymbolRankingItem>> symbolsBySubsystem = allSymbolItems.stream()
                .filter(item -> item.getSubsystemId() != null && !item.getSubsystemId().isBlank())
                .collect(Collectors.groupingBy(SymbolRankingItem::getSubsystemId));

        List<Subsystem> enriched = new ArrayList<>();

        for (Subsystem subsystem : subsystems) {
            List<SymbolRankingItem> members = symbolsBySubsystem.getOrDefault(subsystem.getSubsystemId(), List.of());

            double sumScore = members.stream()
                    .mapToDouble(SymbolRankingItem::getScore)
                    .sum();
            double subsystemScore = calculateSizeAdjustedScore(sumScore, members.size());

            List<String> coreSymbolIds = members.stream()
                    .sorted(Comparator.comparingDouble(SymbolRankingItem::getScore).reversed())
                    .limit(2)
                    .map(SymbolRankingItem::getSymbolId)
                    .toList();

            enriched.add(subsystem.toBuilder()
                    .score(subsystemScore)
                    .coreSymbolIds(coreSymbolIds)
                    .build());
        }

        enriched.sort(Comparator.comparingDouble(Subsystem::getScore).reversed());
        return enriched;
    }

    /**
     * 합계 기반 점수의 군집 크기 편향을 완화한다.
     * - sum / sqrt(n) 형태로 보정해 큰 군집의 과도한 우위를 줄이고, 작은 군집 신호도 보존한다.
     */
    private double calculateSizeAdjustedScore(double totalScore, int memberCount) {
        if (memberCount <= 0) {
            return 0.0;
        }
        return totalScore / Math.sqrt(memberCount);
    }

    public List<SubsystemRankingItem> rankSubsystems(List<Subsystem> enrichedSubsystems) {
        List<SubsystemRankingItem> rankings = new ArrayList<>();

        for (int i = 0; i < enrichedSubsystems.size(); i++) {
            Subsystem ss = enrichedSubsystems.get(i);
            rankings.add(new SubsystemRankingItem(
                    i + 1,
                    ss.getSubsystemId(),
                    ss.getName(),
                    ss.getScore()
            ));
        }

        return rankings;
    }
}