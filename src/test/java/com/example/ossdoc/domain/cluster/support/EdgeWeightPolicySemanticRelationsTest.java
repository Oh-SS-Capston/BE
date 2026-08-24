package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeWeightPolicySemanticRelationsTest {

    private final EdgeWeightPolicy policy = new EdgeWeightPolicy();

    @Test
    @DisplayName("모든 EdgeType에 명시적인 기본 가중치가 존재한다")
    void everyEdgeTypeHasExplicitWeight() {
        Map<EdgeType, Double> expected = new EnumMap<>(EdgeType.class);

        expected.put(EdgeType.EXTENDS, 3.5);
        expected.put(EdgeType.CONTAINS, 3.5);
        expected.put(EdgeType.IMPLEMENTS, 2.5);
        expected.put(EdgeType.HAS_FIELD, 2.5);
        expected.put(EdgeType.ACCESSES_FIELD, 2.0);
        expected.put(EdgeType.CREATES, 2.0);
        expected.put(EdgeType.RETURNS, 2.0);
        expected.put(EdgeType.THROWS, 1.5);
        expected.put(EdgeType.PARAM, 1.5);
        expected.put(EdgeType.CALLS, 1.5);
        expected.put(EdgeType.OVERRIDES, 1.0);
        expected.put(EdgeType.ANNOTATED_WITH, 0.5);
        expected.put(EdgeType.INJECTS, 2.5);
        expected.put(EdgeType.CONFIGURES_BEAN, 2.5);
        expected.put(EdgeType.PROVIDES_SPI, 2.5);
        expected.put(EdgeType.DECLARES_BEAN, 2.0);
        expected.put(EdgeType.LOADS_SERVICE, 2.0);
        expected.put(EdgeType.PUBLISHES_EVENT, 1.5);
        expected.put(EdgeType.LISTENS_EVENT, 1.5);
        expected.put(EdgeType.HANDLES_ENDPOINT, 1.0);
        expected.put(EdgeType.REFLECTS_TYPE, 1.0);
        expected.put(EdgeType.REFLECTS_METHOD, 1.0);
        expected.put(EdgeType.REFLECTS_FIELD, 1.0);
        expected.put(EdgeType.REFLECTS_CONSTRUCTOR, 1.0);

        /*
         * 새로운 EdgeType이 추가됐는데 expected에 등록되지 않았다면
         * 가중치 정책도 같이 검토하도록 테스트를 실패시킨다.
         */
        assertEquals(
                EdgeType.values().length,
                expected.size(),
                "신규 EdgeType이 추가되면 가중치 정책도 함께 갱신해야 함"
        );

        // 모든 EdgeType이 예상한 기본 weight를 반환하는지 확인한다.
        for (EdgeType edgeType : EdgeType.values()) {
            assertEquals(
                    expected.get(edgeType),
                    policy.weightOf(
                            edge(
                                    edgeType,
                                    ResolutionStatus.RESOLVED,
                                    BigDecimal.ONE
                            )
                    ),
                    0.000001,
                    edgeType.name()
            );
        }
    }

    @Test
    @DisplayName("resolution과 confidence가 기본 가중치에 함께 반영된다")
    void appliesResolutionAndConfidenceFactors() {
        Edge edge = edge(
                EdgeType.INJECTS,
                ResolutionStatus.PARTIAL,
                new BigDecimal("0.8")
        );

        /*
         * INJECTS = 2.5
         * PARTIAL = 0.7
         * confidence = 0.8
         *
         * 최종 weight = 2.5 * 0.7 * 0.8 = 1.4
         */
        assertEquals(
                2.5 * 0.7 * 0.8,
                policy.weightOf(edge),
                0.000001
        );
    }

    @Test
    @DisplayName("resolution 또는 confidence가 없으면 중립 계수 1.0을 사용한다")
    void nullPolicyValuesUseNeutralFactor() {
        Edge edge = edge(
                EdgeType.DECLARES_BEAN,
                null,
                null
        );

        assertEquals(
                2.0,
                policy.weightOf(edge),
                0.000001
        );
    }

    @Test
    @DisplayName("UNRESOLVED와 Reflection 관계는 clustering 입력에서 제외한다")
    void clusteringPolicyFiltersNoisyEdges() {
        // target이 확정되지 않은 일반 호출 관계.
        assertEquals(
                false,
                policy.includeInClustering(
                        edge(
                                EdgeType.CALLS,
                                ResolutionStatus.UNRESOLVED,
                                BigDecimal.ONE
                        )
                )
        );

        // target이 확정되었더라도 Reflection 관계는 clustering에서 제외한다.
        assertEquals(
                false,
                policy.includeInClustering(
                        edge(
                                EdgeType.REFLECTS_TYPE,
                                ResolutionStatus.RESOLVED,
                                BigDecimal.ONE
                        )
                )
        );

        // 정상적으로 resolve된 호출 관계는 clustering에 포함한다.
        assertEquals(
                true,
                policy.includeInClustering(
                        edge(
                                EdgeType.CALLS,
                                ResolutionStatus.RESOLVED,
                                BigDecimal.ONE
                        )
                )
        );
    }

    /**
     * 테스트에 필요한 Edge mock을 생성한다.
     */
    private Edge edge(
            EdgeType type,
            ResolutionStatus resolution,
            BigDecimal confidence
    ) {
        Edge edge = mock(Edge.class);

        when(edge.getEdgeType()).thenReturn(type);
        when(edge.getResolution()).thenReturn(resolution);
        when(edge.getConfidence()).thenReturn(confidence);

        return edge;
    }
}