package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * GraphStore의 edge를 clustering graph에 반영할 때 사용할
 * 가중치와 포함 여부를 결정한다.
 *
 * EdgeType, ResolutionStatus, confidence를 함께 고려한다.
 */
@Component
public class EdgeWeightPolicy {

    /**
     * EdgeType별 기본 가중치를 반환한다.
     *
     * 구조적 결합이 강한 관계는 높은 weight를,
     * 단순한 참조 또는 보조 관계는 상대적으로 낮은 weight를 부여한다.
     */
    private double baseWeight(EdgeType type) {
        return switch (type) {
            case EXTENDS, CONTAINS -> 3.5;

            case IMPLEMENTS, HAS_FIELD -> 2.5;

            case ACCESSES_FIELD, CREATES, RETURNS -> 2.0;

            case THROWS, PARAM, CALLS -> 1.5;

            case OVERRIDES -> 1.0;

            case ANNOTATED_WITH -> 0.5;

            // Spring 또는 framework 의미 관계.
            case INJECTS, CONFIGURES_BEAN, PROVIDES_SPI -> 2.5;

            case DECLARES_BEAN, LOADS_SERVICE -> 2.0;

            case PUBLISHES_EVENT, LISTENS_EVENT -> 1.5;

            case HANDLES_ENDPOINT -> 1.0;

            /*
             * Reflection edge에도 기본 weight 정의는 유지한다.
             * 단, 실제 clustering 입력에서는 includeInClustering()에서 제외한다.
             */
            case REFLECTS_TYPE,
                 REFLECTS_METHOD,
                 REFLECTS_FIELD,
                 REFLECTS_CONSTRUCTOR -> 1.0;
        };
    }

    /**
     * 해당 edge를 실제 clustering graph에 포함할지 결정한다.
     *
     * UNRESOLVED 관계는 target이 확정되지 않았으므로 clustering에서 제외한다.
     * Reflection 관계는 runtime 추론 성격이 강하므로 clustering에서는 제외한다.
     *
     * 원본 GraphStore에서 edge 자체를 삭제하는 것은 아니다.
     */
    public boolean includeInClustering(Edge edge) {
        if (edge == null || edge.getEdgeType() == null) {
            return false;
        }

        // target이 확정되지 않은 관계는 subsystem 연결 근거로 사용하지 않는다.
        if (edge.getResolution() == ResolutionStatus.UNRESOLVED) {
            return false;
        }

        return switch (edge.getEdgeType()) {
            case REFLECTS_TYPE,
                 REFLECTS_METHOD,
                 REFLECTS_FIELD,
                 REFLECTS_CONSTRUCTOR -> false;

            default -> true;
        };
    }

    /**
     * ResolutionStatus에 따른 가중치 보정 계수.
     */
    private double resolutionFactor(ResolutionStatus status) {
        if (status == null) {
            return 1.0;
        }

        return switch (status) {
            case RESOLVED -> 1.0;

            // 일부만 확정된 관계이므로 완전한 관계보다 낮은 weight를 적용한다.
            case PARTIAL -> 0.7;

            // 직접 weightOf가 호출되더라도 unresolved는 0으로 처리한다.
            case UNRESOLVED -> 0.0;
        };
    }

    /**
     * confidence를 0.0 ~ 1.0 범위로 제한한다.
     *
     * confidence가 없으면 중립값인 1.0을 사용한다.
     */
    private double confidence(BigDecimal confidence) {
        if (confidence == null) {
            return 1.0;
        }

        return Math.max(
                0.0,
                Math.min(1.0, confidence.doubleValue())
        );
    }

    /**
     * clustering graph에 사용할 최종 edge weight를 계산한다.
     *
     * 최종 weight =
     * EdgeType 기본 weight
     * × ResolutionStatus 계수
     * × confidence
     */
    public double weightOf(Edge edge) {
        return baseWeight(edge.getEdgeType())
                * resolutionFactor(edge.getResolution())
                * confidence(edge.getConfidence());
    }
}