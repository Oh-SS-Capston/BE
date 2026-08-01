package com.example.ossdoc.domain.graphstore.model.promotion.gate;

/**
 * Shadow 검증 성공 이력을 누적하는 범위.
 */
public enum SemanticPromotionGateScope {

    /**
     * 여러 OSS 저장소의 실행 결과를 하나의 전역 이력으로 누적한다.
     */
    GLOBAL,

    /**
     * 동일 저장소의 실행 결과만 별도로 누적한다.
     */
    REPOSITORY
}
