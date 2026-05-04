package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RankingService {

    private final SymbolScoringService symbolScoringService;
    private final SubsystemScoringService subsystemScoringService;

    public RankingResult rank(ProjectedGraph graph, List<Subsystem> subsystems, int topK) {
        Map<String, String> subsystemBySymbolId = buildSubsystemBySymbolId(subsystems);

        List<SymbolRankingItem> allSymbolItems =
                symbolScoringService.scoreSymbols(graph, subsystemBySymbolId);

        List<SymbolRankingItem> topRankedSymbols =
                symbolScoringService.topRankedSymbols(allSymbolItems, topK);

        List<Subsystem> enrichedSubsystems =
                subsystemScoringService.enrichSubsystems(subsystems, allSymbolItems);

        List<SubsystemRankingItem> subsystemRankings =
                subsystemScoringService.rankSubsystems(enrichedSubsystems);

        return new RankingResult(
                topRankedSymbols,
                subsystemRankings,
                enrichedSubsystems
        );
    }

    private Map<String, String> buildSubsystemBySymbolId(List<Subsystem> subsystems) {
        Map<String, String> map = new HashMap<>();
        for (Subsystem subsystem : subsystems) {
            for (String member : subsystem.getMemberSymbolIds()) {
                map.put(member, subsystem.getSubsystemId());
            }
        }
        return map;
    }

    public record RankingResult(
            List<SymbolRankingItem> symbolRankings,
            List<SubsystemRankingItem> subsystemRankings,
            List<Subsystem> enrichedSubsystems
    ) {
    }
}