package com.example.ossdoc.domain.graphstore.model.promotion;

/**
 * GraphStore shadow 후보와 Extraction Relation의 exact parity 상태.
 */
public enum ObservationPromotionCandidateParityStatus {
    MATCHED,
    MISSING_EXTRACTION_RELATION,
    METADATA_MISMATCH,
    EXTRACTION_ONLY
}
