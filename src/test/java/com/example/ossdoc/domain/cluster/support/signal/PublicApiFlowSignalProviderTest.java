package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicApiFlowSignalProviderTest {

    @Mock private ClusterSignalProperties properties;
    @Mock private ClusterSignalProperties.Signals signals;
    @Mock private ClusterSignalProperties.ApiFlowSignal apiFlowConfig;

    private PublicApiFlowSignalProvider provider;

    @BeforeEach
    void setUp() {
        when(properties.getSignals()).thenReturn(signals);
        when(signals.getApiFlow()).thenReturn(apiFlowConfig);
        // provide()는 enabled()를 내부에서 호출하지 않으므로 isEnabled()는 lenient
        lenient().when(apiFlowConfig.isEnabled()).thenReturn(true);
        when(apiFlowConfig.getBaseWeight()).thenReturn(0.10);
        when(apiFlowConfig.getMaxBfsDepth()).thenReturn(4);

        provider = new PublicApiFlowSignalProvider(properties);
    }

    @Test
    @DisplayName("publicApi=true, entryPoint=false인 노드는 seed로 사용되지 않는다")
    void provide_publicApiOnly_notUsedAsSeed() {
        // publicApi=true지만 entryPoint=false → entryIndices 비어 auto-disable
        ProjectedNode node = ProjectedNode.builder()
                .symbolId("sym-A").entryPoint(false).build();

        SignalResult result = provider.provide(
                List.of(node), Map.of("sym-A", 0), List.of());

        assertThat(result.edges()).isEmpty();
        assertThat(result.meta()).containsEntry("auto_disabled", true);
        assertThat(result.meta()).containsEntry("entrypoint_count", 0);
    }

    @Test
    @DisplayName("publicApi=false, entryPoint=true인 노드는 seed로 사용된다")
    void provide_entryPointOnly_usedAsSeed() {
        // 5개 노드 — flow set이 80% 초과되지 않도록 A가 B·C에만 도달하게 설정
        // A(entryPoint=true) → B → C, D·E는 고립
        ProjectedNode nodeA = ProjectedNode.builder().symbolId("sym-A").entryPoint(true).build();
        ProjectedNode nodeB = ProjectedNode.builder().symbolId("sym-B").entryPoint(false).build();
        ProjectedNode nodeC = ProjectedNode.builder().symbolId("sym-C").entryPoint(false).build();
        ProjectedNode nodeD = ProjectedNode.builder().symbolId("sym-D").entryPoint(false).build();
        ProjectedNode nodeE = ProjectedNode.builder().symbolId("sym-E").entryPoint(false).build();

        List<ProjectedNode> nodes = List.of(nodeA, nodeB, nodeC, nodeD, nodeE);
        Map<String, Integer> indexMap = Map.of(
                "sym-A", 0, "sym-B", 1, "sym-C", 2, "sym-D", 3, "sym-E", 4);

        // A→B, B→C 코드 엣지 (flow set = {A,B,C} = 3/5 = 60% < 80%)
        List<ProjectedEdge> codeEdges = List.of(
                new ProjectedEdge(0, 1, 1.0),
                new ProjectedEdge(1, 2, 1.0)
        );

        SignalResult result = provider.provide(nodes, indexMap, codeEdges);

        // sym-A가 seed → flow set {A,B,C} → 가상 엣지 생성
        assertThat(result.meta()).containsEntry("entrypoint_count", 1);
        assertThat(result.edges()).isNotEmpty();
        assertThat(result.meta()).doesNotContainKey("auto_disabled");
    }
}
