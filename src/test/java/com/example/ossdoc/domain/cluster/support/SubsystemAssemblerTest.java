package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemAssemblerTest {

    private final SubsystemAssembler subsystemAssembler =
            new SubsystemAssembler(new PackageTokenExtractor());

    @Test
    @DisplayName("정상 cluster와 연결되지 않은 작은 cluster는 misc로 이동한다")
    void assemble_shouldAbsorbSmallClustersIntoMiscSubsystem() {
        ProjectedNode authController = node(
                "sym1",
                "com.example.auth",
                "AuthController",
                true
        );

        ProjectedNode authService = node(
                "sym2",
                "com.example.auth",
                "AuthService",
                false
        );

        ProjectedNode paymentClient = node(
                "sym3",
                "com.example.payment",
                "PaymentClient",
                true
        );

        ProjectedNode mailSender = node(
                "sym4",
                "com.example.notification",
                "MailSender",
                false
        );

        /*
         * cluster assignment:
         *
         * cluster 0 = authController, authService
         * cluster 1 = paymentClient
         * cluster 2 = mailSender
         *
         * minClusterSize = 2이므로 cluster 1과 2는 작은 cluster다.
         *
         * graph에 cluster 간 edge가 없으므로
         * paymentClient와 mailSender는 misc로 이동해야 한다.
         */
        ProjectedGraph graph = ProjectedGraph.builder()
                .runId("run-1")
                .nodes(
                        List.of(
                                authController,
                                authService,
                                paymentClient,
                                mailSender
                        )
                )
                .edges(List.of())
                .nodeIndexMap(
                        Map.of(
                                "sym1", 0,
                                "sym2", 1,
                                "sym3", 2,
                                "sym4", 3
                        )
                )
                .build();

        int[] clusters = {0, 0, 1, 2};

        List<Subsystem> subsystems =
                subsystemAssembler.assemble(
                        graph,
                        clusters,
                        2
                );

        assertThat(subsystems).hasSize(2);

        Subsystem main = subsystems.get(0);

        assertThat(main.getMemberSymbolIds())
                .containsExactly(
                        "sym1",
                        "sym2"
                );

        Subsystem misc = subsystems.get(1);

        assertThat(misc.getName())
                .isEqualTo("misc");

        assertThat(misc.getMemberSymbolIds())
                .containsExactly(
                        "sym3",
                        "sym4"
                );

        assertThat(misc.getEntrySymbolIds())
                .containsExactly("sym3");
    }

    @Test
    @DisplayName("작은 cluster는 가장 강하게 연결된 정상 cluster로 흡수한다")
    void assemble_shouldAbsorbSmallClusterIntoStrongestNeighbor() {
        ProjectedNode authController = node(
                "A1",
                "com.example.auth",
                "AuthController",
                false
        );

        ProjectedNode authService = node(
                "A2",
                "com.example.auth",
                "AuthService",
                false
        );

        ProjectedNode tokenResolver = node(
                "B1",
                "com.example.auth.token",
                "JwtTokenResolver",
                false
        );

        /*
         * cluster 0 = AuthController, AuthService
         * cluster 1 = JwtTokenResolver
         *
         * minClusterSize = 2이므로 cluster 1은 작은 cluster다.
         *
         * AuthService와 JwtTokenResolver 사이에 weight 2.5 관계가 있으므로
         * JwtTokenResolver는 misc가 아닌 cluster 0으로 흡수되어야 한다.
         */
        ProjectedGraph graph = ProjectedGraph.builder()
                .runId("run-1")
                .nodes(
                        List.of(
                                authController,
                                authService,
                                tokenResolver
                        )
                )
                .edges(
                        List.of(
                                new ProjectedEdge(
                                        1,
                                        2,
                                        2.5
                                )
                        )
                )
                .nodeIndexMap(
                        Map.of(
                                "A1", 0,
                                "A2", 1,
                                "B1", 2
                        )
                )
                .build();

        List<Subsystem> subsystems =
                subsystemAssembler.assemble(
                        graph,
                        new int[]{0, 0, 1},
                        2
                );

        // 작은 cluster가 정상 cluster에 흡수됐으므로 subsystem은 하나만 존재해야 한다.
        assertThat(subsystems)
                .hasSize(1);

        assertThat(
                subsystems
                        .get(0)
                        .getMemberSymbolIds()
        )
                .containsExactly(
                        "A1",
                        "A2",
                        "B1"
                );

        // misc subsystem이 생성되면 안 된다.
        assertThat(subsystems)
                .noneMatch(
                        subsystem ->
                                "misc".equals(
                                        subsystem.getName()
                                )
                );
    }

    /**
     * 테스트용 ProjectedNode 생성.
     */
    private ProjectedNode node(
            String id,
            String pkg,
            String simpleName,
            boolean entryPoint
    ) {
        return ProjectedNode.builder()
                .symbolId(id)
                .qualifiedName(pkg + "." + simpleName)
                .simpleName(simpleName)
                .packageName(pkg)
                .entryPoint(entryPoint)
                .build();
    }
}