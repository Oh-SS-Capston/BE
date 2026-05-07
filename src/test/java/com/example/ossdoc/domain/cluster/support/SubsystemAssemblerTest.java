package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemAssemblerTest {

    private final SubsystemAssembler subsystemAssembler = new SubsystemAssembler(new PackageTokenExtractor());

    @Test
    @DisplayName("minClusterSize 미만 군집은 misc subsystem으로 흡수한다")
    void assemble_shouldAbsorbSmallClustersIntoMiscSubsystem() {
        // given
        ProjectedNode n1 = ProjectedNode.builder()
                .symbolId("sym1")
                .qualifiedName("com.example.auth.AuthController")
                .simpleName("AuthController")
                .packageName("com.example.auth")
                .moduleId(null)
                .publicApi(true)
                .build();

        ProjectedNode n2 = ProjectedNode.builder()
                .symbolId("sym2")
                .qualifiedName("com.example.auth.AuthService")
                .simpleName("AuthService")
                .packageName("com.example.auth")
                .moduleId(null)
                .publicApi(false)
                .build();

        ProjectedNode n3 = ProjectedNode.builder()
                .symbolId("sym3")
                .qualifiedName("com.example.payment.PaymentClient")
                .simpleName("PaymentClient")
                .packageName("com.example.payment")
                .moduleId(null)
                .publicApi(true)
                .build();

        ProjectedNode n4 = ProjectedNode.builder()
                .symbolId("sym4")
                .qualifiedName("com.example.notification.MailSender")
                .simpleName("MailSender")
                .packageName("com.example.notification")
                .moduleId(null)
                .publicApi(false)
                .build();

        ProjectedGraph graph = ProjectedGraph.builder()
                .runId("run-1")
                .nodes(List.of(n1, n2, n3, n4))
                .edges(List.of())
                .nodeIndexMap(Map.of(
                        "sym1", 0,
                        "sym2", 1,
                        "sym3", 2,
                        "sym4", 3
                ))
                .build();

        int[] clusters = {0, 0, 1, 2};

        // when
        List<Subsystem> subsystems = subsystemAssembler.assemble(graph, clusters, 2);

        // then
        assertThat(subsystems).hasSize(2);

        Subsystem main = subsystems.get(0);
        assertThat(main.getSubsystemId()).isEqualTo("ss_001");
        assertThat(main.getMemberSymbolIds()).containsExactly("sym1", "sym2");

        Subsystem misc = subsystems.get(1);
        assertThat(misc.getSubsystemId()).isEqualTo("ss_002");
        assertThat(misc.getName()).isEqualTo("misc");
        assertThat(misc.getMemberSymbolIds()).containsExactly("sym3", "sym4");
        assertThat(misc.getEntrySymbolIds()).containsExactly("sym3");
    }
}