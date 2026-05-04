package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.ranking.SymbolRankingItem;
import com.example.ossdoc.domain.cluster.support.ScoreNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolScoringServiceTest {

    private final SymbolScoringService symbolScoringService =
            new SymbolScoringService(new ScoreNormalizer());

    @Test
    @DisplayName("public API controller가 non-public symbol보다 높은 점수를 가진다")
    void scoreSymbols_shouldPrioritizePublicApiController() {
        // given
        ProjectedGraph graph = ProjectedGraph.builder()
                .runId("run-test")
                .nodes(List.of(
                        ProjectedNode.builder()
                                .symbolId("sym_controller")
                                .qualifiedName("com.example.auth.AuthController")
                                .simpleName("AuthController")
                                .packageName("com.example.auth")
                                .moduleId("m1")
                                .publicApi(true)
                                .build(),
                        ProjectedNode.builder()
                                .symbolId("sym_service")
                                .qualifiedName("com.example.auth.AuthService")
                                .simpleName("AuthService")
                                .packageName("com.example.auth")
                                .moduleId("m1")
                                .publicApi(false)
                                .build()
                ))
                .edges(List.of(
                        new ProjectedEdge(0, 1, 1.0)
                ))
                .nodeIndexMap(Map.of(
                        "sym_controller", 0,
                        "sym_service", 1
                ))
                .build();

        Map<String, String> subsystemBySymbolId = Map.of(
                "sym_controller", "ss_001",
                "sym_service", "ss_001"
        );

        // when
        List<SymbolRankingItem> allItems =
                symbolScoringService.scoreSymbols(graph, subsystemBySymbolId);

        List<SymbolRankingItem> ranked =
                symbolScoringService.topRankedSymbols(allItems, 10);

        // then
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getSymbolId()).isEqualTo("sym_controller");
        assertThat(ranked.get(0).getApiScore()).isEqualTo(1.0);
        assertThat(ranked.get(1).getSymbolId()).isEqualTo("sym_service");
        assertThat(ranked.get(1).getApiScore()).isEqualTo(0.0);
    }
}