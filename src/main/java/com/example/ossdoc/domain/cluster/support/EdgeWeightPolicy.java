package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EdgeWeightPolicy {
    private double baseWeight(EdgeType type){
        return switch(type){
            case EXTENDS        -> 3.5; // IS-A, 단일 상속, 최강 결합
            case CONTAINS       -> 3.5; // Inner class — outer와 아키텍처상 불가분 (투영 그래프에서는 TYPE→TYPE만 남음)
            case IMPLEMENTS     -> 2.5; // 계약 이행이지만 표준 인터페이스 노이즈 반영
            case HAS_FIELD      -> 2.5; // 구성 관계 — 상속 없는 계층의 주요 결합 신호
            case ACCESSES_FIELD -> 2.0; // 내부 구현 직접 의존, CALLS보다 강함
            case CREATES        -> 2.0; // 객체 생성에 따른 구체 타입 의존
            case RETURNS        -> 2.0; // 팩토리/빌더 생성 관계
            case THROWS         -> 1.5; // 사용 계약, 예외는 별도 계층이 되는 경우가 많음
            case PARAM          -> 1.5; // 고빈도 노이즈, 누적 가중치로 의미 생성
            case CALLS          -> 1.5; // 가장 흔한 사용 결합
            case OVERRIDES      -> 1.0; // EXTENDS/IMPLEMENTS 보조 신호
            case ANNOTATED_WITH -> 0.5; // 프레임워크 어노테이션 노이즈
        };
    }
    private double resolutionFactor(ResolutionStatus status){
        return switch (status){
            case RESOLVED -> 1.0;
            case PARTIAL -> 0.7;
            case UNRESOLVED -> 0.3;
        };
    }
    private double confidence(BigDecimal confidence){
        return confidence == null ? 1.0 : confidence.doubleValue();
    }
    public double weightOf(Edge edge){
        double base = baseWeight(edge.getEdgeType());
        double resolutionFactor = resolutionFactor(edge.getResolution());
        double confidence = confidence(edge.getConfidence());
        return base * resolutionFactor * confidence;
    }

}
