package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RankingServiceTest {

    @Test
    @DisplayName("RankingService는 symbol scoring과 subsystem scoring 결과를 조립한다")
    void rank_shouldAssembleResults() {
        // given
        SymbolScoringService symbolScoringService = mock(SymbolScoringService.class);
        SubsystemScoringService subsystemScoringService = mock(SubsystemScoringService.class);

        RankingService rankingService = new RankingService(
                symbolScoringService,
                subsystemScoringService
        );

        ProjectedGraph graph = mock(ProjectedGraph.class);

        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("auth")
                        .score(0.0)
                        .memberSymbolIds(List.of("a1", "a2"))
                        .entrySymbolIds(List.of("a1"))
                        .coreSymbolIds(List.of())
                        .packageRoots(List.of("com.example.auth"))
                        .build()
        );

        List<SymbolRankingItem> allItems = List.of(
                SymbolRankingItem.builder().symbolId("a1").subsystemId("ss_001").score(0.9).build()
        );

        List<SymbolRankingItem> topItems = List.of(
                SymbolRankingItem.builder().rank(1).symbolId("a1").subsystemId("ss_001").score(0.9).build()
        );

        List<Subsystem> enrichedSubsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("auth")
                        .score(0.9)
                        .memberSymbolIds(List.of("a1", "a2"))
                        .entrySymbolIds(List.of("a1"))
                        .coreSymbolIds(List.of("a1"))
                        .packageRoots(List.of("com.example.auth"))
                        .build()
        );

        List<SubsystemRankingItem> subsystemRankings = List.of(
                new SubsystemRankingItem(1, "ss_001", "auth", 0.9)
        );

        when(symbolScoringService.scoreSymbols(any(), any())).thenReturn(allItems);
        when(symbolScoringService.topRankedSymbols(allItems, 10)).thenReturn(topItems);
        when(subsystemScoringService.enrichSubsystems(subsystems, allItems)).thenReturn(enrichedSubsystems);
        when(subsystemScoringService.rankSubsystems(enrichedSubsystems)).thenReturn(subsystemRankings);

        // when
        RankingService.RankingResult result = rankingService.rank(graph, subsystems, 10);

        // then
        assertThat(result.symbolRankings()).hasSize(1);
        assertThat(result.subsystemRankings()).hasSize(1);
        assertThat(result.enrichedSubsystems()).hasSize(1);

        assertThat(result.symbolRankings().get(0).getSymbolId()).isEqualTo("a1");
        assertThat(result.subsystemRankings().get(0).getSubsystemId()).isEqualTo("ss_001");
        assertThat(result.enrichedSubsystems().get(0).getCoreSymbolIds()).containsExactly("a1");

        verify(symbolScoringService).scoreSymbols(any(), any());
        verify(symbolScoringService).topRankedSymbols(allItems, 10);
        verify(subsystemScoringService).enrichSubsystems(subsystems, allItems);
        verify(subsystemScoringService).rankSubsystems(enrichedSubsystems);
    }
}