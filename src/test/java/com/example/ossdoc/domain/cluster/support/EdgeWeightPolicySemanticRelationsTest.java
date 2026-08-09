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

    private final EdgeWeightPolicy policy =
            new EdgeWeightPolicy();

    @Test
    @DisplayName("모든 EdgeType에 명시적인 기본 가중치가 존재한다")
    void everyEdgeTypeHasExplicitWeight() {
        Map<EdgeType, Double> expected =
                new EnumMap<>(EdgeType.class);

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

        assertEquals(
                EdgeType.values().length,
                expected.size(),
                "신규 EdgeType이 추가되면 가중치 정책도 함께 갱신해야 함"
        );

        for (EdgeType edgeType : EdgeType.values()) {
            assertEquals(
                    expected.get(edgeType),
                    policy.weightOf(edge(
                            edgeType,
                            ResolutionStatus.RESOLVED,
                            BigDecimal.ONE
                    )),
                    0.000001,
                    edgeType.name()
            );
        }
    }

    @Test
    @DisplayName("resolution과 confidence가 의미 Edge 기본 가중치에 곱해진다")
    void appliesResolutionAndConfidenceFactors() {
        Edge edge = edge(
                EdgeType.INJECTS,
                ResolutionStatus.PARTIAL,
                new BigDecimal("0.8")
        );

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

    private Edge edge(
            EdgeType type,
            ResolutionStatus resolution,
            BigDecimal confidence
    ) {
        Edge edge = mock(Edge.class);

        when(edge.getEdgeType())
                .thenReturn(type);

        when(edge.getResolution())
                .thenReturn(resolution);

        when(edge.getConfidence())
                .thenReturn(confidence);

        return edge;
    }
}
