package com.example.ossdoc.domain.graphstore.model.promotion.gate;

/**
 * 의미 Relation 저장 책임 전환 Gate의 현재 판정.
 */
public enum SemanticPromotionGateStatus {

    /**
     * Gate 평가 자체가 비활성화된 상태.
     */
    DISABLED,

    /**
     * 동일 runId가 이미 평가되어 이력 변경 없이 무시된 상태.
     */
    DUPLICATE_RUN_IGNORED,

    /**
     * 현재 실행이 Shadow 검증 조건을 통과하지 못해 연속 성공 이력이 초기화된 상태.
     */
    BLOCKED,

    /**
     * 현재 실행은 통과했지만 누적 조건을 아직 충족하지 못한 상태.
     */
    WARMING_UP,

    /**
     * 누적 조건은 충족했지만 후보 저장 기능 플래그가 비활성화된 상태.
     */
    READY_BUT_PERSISTENCE_DISABLED,

    /**
     * 누적 조건과 후보 저장 기능 플래그가 모두 활성화된 상태.
     *
     * 실제 후보 저장은 후속 단계에서 별도 writer가 연결된 뒤 수행한다.
     */
    READY
}
