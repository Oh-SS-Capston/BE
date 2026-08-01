package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EdgeWeightPolicy {

    /**
     * 관계 종류별 기본 결합 강도.
     *
     * 구조 관계는 코드 구조상의 직접 결합을 중심으로,
     * 의미 관계는 프레임워크·런타임 의존 강도와 노이즈 가능성을
     * 함께 고려해 가중치를 설정한다.
     */
    private double baseWeight(EdgeType type) {
        return switch (type) {
            // ── 구조 관계 ─────────────────────────────────────────────
            case EXTENDS ->
                    3.5; // IS-A, 단일 상속, 최강 결합

            case CONTAINS ->
                    3.5; // Inner class — outer와 아키텍처상 불가분

            case IMPLEMENTS ->
                    2.5; // 계약 이행

            case HAS_FIELD ->
                    2.5; // 구성 관계

            case ACCESSES_FIELD ->
                    2.0; // 내부 구현 직접 의존

            case CREATES ->
                    2.0; // 구체 타입 생성 의존

            case RETURNS ->
                    2.0; // 팩토리·빌더 생성 관계

            case THROWS ->
                    1.5; // 예외 계약

            case PARAM ->
                    1.5; // 메서드 시그니처 의존

            case CALLS ->
                    1.5; // 호출 결합

            case OVERRIDES ->
                    1.0; // 상속·구현의 보조 신호

            case ANNOTATED_WITH ->
                    0.5; // 프레임워크 어노테이션 노이즈 가능성

            // ── 의미 관계 ─────────────────────────────────────────────
            case INJECTS ->
                    2.5; // 런타임 객체 결합, 구성 관계에 가까움

            case CONFIGURES_BEAN ->
                    2.5; // Bean 구성 및 조립 책임

            case PROVIDES_SPI ->
                    2.5; // SPI 계약 구현·제공 관계

            case DECLARES_BEAN ->
                    2.0; // Bean 생성 책임

            case LOADS_SERVICE ->
                    2.0; // ServiceLoader 기반 런타임 의존

            case PUBLISHES_EVENT ->
                    1.5; // 이벤트 타입에 대한 발행 의존

            case LISTENS_EVENT ->
                    1.5; // 이벤트 타입에 대한 구독 의존

            case HANDLES_ENDPOINT ->
                    1.0; // 외부 진입점 책임 신호, 내부 결합은 비교적 약함

            case REFLECTS_TYPE ->
                    1.0; // 문자열·동적 타입 참조, 오탐 가능성 반영

            case REFLECTS_METHOD ->
                    1.0; // 동적 메서드 참조

            case REFLECTS_FIELD ->
                    1.0; // 동적 필드 참조

            case REFLECTS_CONSTRUCTOR ->
                    1.0; // 동적 생성자 참조
        };
    }

    private double resolutionFactor(
            ResolutionStatus status
    ) {
        if (status == null) {
            return 1.0;
        }

        return switch (status) {
            case RESOLVED -> 1.0;
            case PARTIAL -> 0.7;
            case UNRESOLVED -> 0.3;
        };
    }

    private double confidence(
            BigDecimal confidence
    ) {
        return confidence == null
                ? 1.0
                : confidence.doubleValue();
    }

    public double weightOf(Edge edge) {
        double base =
                baseWeight(edge.getEdgeType());

        double resolutionFactor =
                resolutionFactor(edge.getResolution());

        double confidence =
                confidence(edge.getConfidence());

        return base
                * resolutionFactor
                * confidence;
    }
}
