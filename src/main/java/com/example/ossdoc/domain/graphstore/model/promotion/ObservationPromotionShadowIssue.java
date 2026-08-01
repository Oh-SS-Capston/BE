package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.List;

/**
 * Observation 한 건에 대한 shadow 비교 결과.
 */
public record ObservationPromotionShadowIssue(
        int observationIndex,
        String observationKind,
        String siteSymbol,
        String targetReference,
        ObservationPromotionShadowStatus status,
        String relationKind,
        String relationSourceSymbol,
        String relationDestination,
        List<String> reasons
) {

    public ObservationPromotionShadowIssue {
        reasons = reasons == null
                ? List.of()
                : List.copyOf(reasons);
    }

    public boolean isMismatch() {
        return status != ObservationPromotionShadowStatus.MATCHED
                && status != ObservationPromotionShadowStatus.NOT_PROMOTABLE;
    }

    public String summary() {
        return "index="
                + observationIndex
                + ", observation="
                + safe(observationKind)
                + ", site="
                + safe(siteSymbol)
                + ", status="
                + status
                + ", relation="
                + safe(relationKind)
                + ", reasons="
                + reasons;
    }

    private String safe(String value) {
        return value == null ? "<null>" : value;
    }
}
