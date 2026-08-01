package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.List;

/**
 * 후보 Relation key 한 건에 대한 exact parity 결과.
 */
public record ObservationPromotionCandidateParityIssue(
        String relationKey,
        ObservationPromotionCandidateParityStatus status,
        Integer observationIndex,
        String observationKind,
        List<String> reasons
) {

    public ObservationPromotionCandidateParityIssue {
        reasons = reasons == null
                ? List.of()
                : List.copyOf(reasons);
    }

    public boolean isMismatch() {
        return status != ObservationPromotionCandidateParityStatus.MATCHED;
    }

    public String summary() {
        return "key="
                + relationKey
                + ", status="
                + status
                + ", observationIndex="
                + observationIndex
                + ", observationKind="
                + observationKind
                + ", reasons="
                + reasons;
    }
}
