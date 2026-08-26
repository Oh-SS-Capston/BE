package com.example.ossdoc.domain.graphstore.model.promotion;

/**
 * Observation을 Relation으로 승격할 때 Evidence를 승계하는 방식.
 */
public enum ObservationEvidencePolicy {

    /**
     * 현재 Observation의 evidenceIds만 순서·중복 제거 후 승계한다.
     */
    SOURCE_OBSERVATION,

    /**
     * 현재 Observation과 매칭된 후보 Observation의 Evidence를 합친다.
     * 현재 DI resolver가 injection site와 provider Evidence를 합치는 방식이다.
     */
    SOURCE_AND_MATCHED_OBSERVATIONS
}
