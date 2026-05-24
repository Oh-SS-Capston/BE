package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeidenCommunityServiceTest {

    private final LeidenCommunityService leidenCommunityService = new LeidenCommunityService();

    @Test
    @DisplayName("강하게 연결된 두 그룹은 서로 다른 community로 분리된다")
    void detect_shouldSplitTwoDenseGroups() {
        // given
        List<ProjectedNode> nodes = List.of(
                ProjectedNode.builder()
                        .symbolId("A1")
                        .qualifiedName("com.example.auth.AuthController")
                        .simpleName("AuthController")
                        .packageName("com.example.auth")
                        .moduleId("mod-auth")
                        .build(),
                ProjectedNode.builder()
                        .symbolId("A2")
                        .qualifiedName("com.example.auth.AuthService")
                        .simpleName("AuthService")
                        .packageName("com.example.auth")
                        .moduleId("mod-auth")
                        .build(),
                ProjectedNode.builder()
                        .symbolId("A3")
                        .qualifiedName("com.example.auth.JwtProvider")
                        .simpleName("JwtProvider")
                        .packageName("com.example.auth")
                        .moduleId("mod-auth")
                        .build(),

                ProjectedNode.builder()
                        .symbolId("B1")
                        .qualifiedName("com.example.user.UserController")
                        .simpleName("UserController")
                        .packageName("com.example.user")
                        .moduleId("mod-user")
                        .build(),
                ProjectedNode.builder()
                        .symbolId("B2")
                        .qualifiedName("com.example.user.UserService")
                        .simpleName("UserService")
                        .packageName("com.example.user")
                        .moduleId("mod-user")
                        .build(),
                ProjectedNode.builder()
                        .symbolId("B3")
                        .qualifiedName("com.example.user.UserRepository")
                        .simpleName("UserRepository")
                        .packageName("com.example.user")
                        .moduleId("mod-user")
                        .build()
        );

        List<ProjectedEdge> edges = List.of(
                // auth 내부 강한 연결
                new ProjectedEdge(0, 1, 5.0),
                new ProjectedEdge(1, 2, 5.0),
                new ProjectedEdge(0, 2, 4.0),

                // user 내부 강한 연결
                new ProjectedEdge(3, 4, 5.0),
                new ProjectedEdge(4, 5, 5.0),
                new ProjectedEdge(3, 5, 4.0),

                // 그룹 간 약한 연결
                new ProjectedEdge(1, 4, 0.2)
        );

        ProjectedGraph graph = ProjectedGraph.builder()
                .runId("run-test")
                .nodes(nodes)
                .edges(edges)
                .nodeIndexMap(Map.of(
                        "A1", 0,
                        "A2", 1,
                        "A3", 2,
                        "B1", 3,
                        "B2", 4,
                        "B3", 5
                ))
                .build();

        // when
        CommunityResult result = leidenCommunityService.detect(graph, 0.01, 10);

        // then
        int[] clusters = result.getClusters();

        assertThat(clusters).hasSize(6);

        // auth 3개는 같은 cluster
        assertThat(clusters[0]).isEqualTo(clusters[1]);
        assertThat(clusters[1]).isEqualTo(clusters[2]);

        // user 3개는 같은 cluster
        assertThat(clusters[3]).isEqualTo(clusters[4]);
        assertThat(clusters[4]).isEqualTo(clusters[5]);

        // auth 그룹과 user 그룹은 다른 cluster
        assertThat(clusters[0]).isNotEqualTo(clusters[3]);
    }
}