package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubsystemScoringService {
    /* 역할
    * - subsystem별 멤버 symbol 점수 묶기
    * - subsystem score 계산
    * - core symbol 추출
    * - subsystem ranking 생성
    * */
    public List<Subsystem> enrichSubsystems(List<Subsystem> subsystems, List<SymbolRankingItem> allSymbolItems) {
        // 방어 로직: subsystemId가 비어 있는 항목은 groupBy 이전에 제외해 NPE를 방지한다.
        Map<String, List<SymbolRankingItem>> symbolsBySubsystem = allSymbolItems.stream()
                .filter(item -> item.getSubsystemId() != null && !item.getSubsystemId().isBlank())
                .collect(Collectors.groupingBy(SymbolRankingItem::getSubsystemId));

        List<Subsystem> enriched = new ArrayList<>();

        for (Subsystem subsystem : subsystems) {
            List<SymbolRankingItem> members = symbolsBySubsystem.getOrDefault(subsystem.getSubsystemId(), List.of());

            double subsystemScore = members.stream()
                    .mapToDouble(SymbolRankingItem::getScore)
                    .sum();

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
