package com.example.ossdoc.domain.graphstore.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeInferencePolicyTest {

    private final EdgeInferencePolicy policy = new EdgeInferencePolicy();

    @Test
    @DisplayName("UNRESOLVED 또는 confidence 0.4 미만 edge는 후단 추론에서 제외한다")
    void shouldRejectUnresolvedOrLowConfidenceEdges() {
        Edge unresolved = baseEdge();
        when(unresolved.getResolution()).thenReturn(ResolutionStatus.UNRESOLVED);
        when(unresolved.getConfidence()).thenReturn(new BigDecimal("0.95"));

        Edge low = baseEdge();
        when(low.getResolution()).thenReturn(ResolutionStatus.PARTIAL);
        when(low.getConfidence()).thenReturn(new BigDecimal("0.39"));

        assertThat(policy.isUsableForInference(unresolved)).isFalse();
        assertThat(policy.isUsableForInference(low)).isFalse();
    }

    @Test
    @DisplayName("default_visible=true인 usable edge는 강한 semantic signal로 취급한다")
    void shouldHonorDefaultVisibleForHighTrust() {
        Edge edge = baseEdge();
        when(edge.getResolution()).thenReturn(ResolutionStatus.PARTIAL);
        when(edge.getConfidence()).thenReturn(new BigDecimal("0.70"));
        when(edge.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode()
                .put("default_visible", true));

        assertThat(policy.isUsableForInference(edge)).isTrue();
        assertThat(policy.isHighTrust(edge)).isTrue();
    }

    @Test
    @DisplayName("구버전처럼 confidence가 비어 있으면 fallback confidence를 사용한다")
    void shouldUseFallbackForLegacyEdges() {
        Edge edge = baseEdge();
        when(edge.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        when(edge.getConfidence()).thenReturn(null);

        assertThat(policy.isUsableForInference(edge)).isTrue();
        assertThat(policy.effectiveConfidence(edge, new BigDecimal("0.85")))
                .isEqualByComparingTo("0.85");
    }

    private Edge baseEdge() {
        Edge edge = mock(Edge.class);
        when(edge.getEdgeType()).thenReturn(EdgeType.CALLS);
        when(edge.getFromSymbol()).thenReturn(mock(SymbolEntity.class));
        return edge;
    }
}
