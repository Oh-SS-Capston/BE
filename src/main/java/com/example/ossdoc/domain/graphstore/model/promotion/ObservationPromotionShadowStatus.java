package com.example.ossdoc.domain.graphstore.model.promotion;

/**
 * Observation과 Extraction 의미 Relation의 shadow 비교 결과.
 */
public enum ObservationPromotionShadowStatus {
    MATCHED,
    MISSING_RELATION,
    KIND_MISMATCH,
    METADATA_MISMATCH,
    EVIDENCE_MISMATCH,
    NOT_PROMOTABLE
}
