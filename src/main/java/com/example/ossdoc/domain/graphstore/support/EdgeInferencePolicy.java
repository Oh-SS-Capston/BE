package com.example.ossdoc.domain.graphstore.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * GraphStore edge를 API/Rule 같은 후단 추론 단계에서 사용할 때의 공통 신뢰도 정책.
 *
 * <p>Extraction 단계에서 계산한 resolution/confidence/default_visible을 그대로 소비하되,
 * 기존 데이터처럼 confidence가 비어 있는 edge는 하위 호환을 위해 중립값으로 취급한다.</p>
 */
@Component
public class EdgeInferencePolicy {

    public static final BigDecimal MIN_INFERENCE_CONFIDENCE = new BigDecimal("0.4000");
    public static final BigDecimal HIGH_TRUST_CONFIDENCE = new BigDecimal("0.7500");

    /**
     * 후단 추론에 사용할 수 있는 최소 조건.
     * UNRESOLVED 또는 confidence < 0.4 관계는 기본 추론 입력에서 제외한다.
     */
    public boolean isUsableForInference(Edge edge) {
        if (edge == null || edge.getEdgeType() == null || edge.getFromSymbol() == null) {
            return false;
        }
        if (edge.getResolution() == ResolutionStatus.UNRESOLVED) {
            return false;
        }

        BigDecimal confidence = edge.getConfidence();
        return confidence == null || confidence.compareTo(MIN_INFERENCE_CONFIDENCE) >= 0;
    }

    /**
     * API의 강한 진입점/확장점 신호로 바로 승격할 수 있는 edge인지 판정한다.
     * Extraction 정책이 default_visible=true로 확정한 경우를 우선 존중한다.
     */
    public boolean isHighTrust(Edge edge) {
        if (!isUsableForInference(edge)) {
            return false;
        }

        Boolean defaultVisible = defaultVisible(edge);
        if (Boolean.TRUE.equals(defaultVisible)) {
            return true;
        }

        return edge.getResolution() == ResolutionStatus.RESOLVED
                && effectiveConfidence(edge, BigDecimal.ONE)
                .compareTo(HIGH_TRUST_CONFIDENCE) >= 0;
    }

    /**
     * downstream signal에 전달할 confidence.
     * 새 facts에는 edge.confidence를 사용하고, 구버전 edge에는 호출자가 준 fallback을 사용한다.
     */
    public BigDecimal effectiveConfidence(Edge edge, BigDecimal fallback) {
        BigDecimal value = edge == null ? null : edge.getConfidence();
        if (value == null) {
            value = fallback == null ? BigDecimal.ONE : fallback;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    /** attrs.default_visible이 명시된 경우에만 Boolean을 반환한다. */
    public Boolean defaultVisible(Edge edge) {
        if (edge == null) {
            return null;
        }
        JsonNode attrs = edge.getAttrs();
        if (attrs == null || attrs.isNull()) {
            return null;
        }
        JsonNode node = attrs.get("default_visible");
        if (node == null || !node.isBoolean()) {
            return null;
        }
        return node.asBoolean();
    }
}
