package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ranking.SubsystemRankingItem;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemScoringServiceTest {

    private final SubsystemScoringService subsystemScoringService =
            new SubsystemScoringService();

    @Test
    @DisplayName("subsystem score를 계산하고 상위 2개 symbol을 core로 채운다")
    void enrichSubsystems_shouldFillScoreAndCoreSymbols() {
        // given
        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("auth")
                        .score(0.0)
                        .memberSymbolIds(List.of("a1", "a2", "a3"))
                        .entrySymbolIds(List.of("a1"))
                        .coreSymbolIds(List.of())
                        .packageRoots(List.of("com.example.auth"))
                        .build()
        );

        List<SymbolRankingItem> symbolItems = List.of(
                SymbolRankingItem.builder().symbolId("a1").subsystemId("ss_001").score(0.9).build(),
                SymbolRankingItem.builder().symbolId("a2").subsystemId("ss_001").score(0.7).build(),
                SymbolRankingItem.builder().symbolId("a3").subsystemId("ss_001").score(0.3).build()
        );

        // when
        List<Subsystem> enriched =
                subsystemScoringService.enrichSubsystems(subsystems, symbolItems);

        List<SubsystemRankingItem> rankings =
                subsystemScoringService.rankSubsystems(enriched);

        // then
        assertThat(enriched).hasSize(1);
        Subsystem ss = enriched.get(0);

        assertThat(ss.getScore()).isEqualTo(1.9);
        assertThat(ss.getCoreSymbolIds()).containsExactly("a1", "a2");

        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).getSubsystemId()).isEqualTo("ss_001");
        assertThat(rankings.get(0).getScore()).isEqualTo(1.9);
    }
}