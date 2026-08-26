package com.example.ossdoc.domain.graphstore.model.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;

/**
 * 하나의 Observation에서 GraphStore가 독립적으로 생성한 shadow Relation 후보.
 */
public record ObservationPromotionShadowCandidate(
        int observationIndex,
        String observationKind,
        NormalizedRelationFact relation
) {

    public ObservationPromotionShadowCandidate {
        if (relation == null) {
            throw new IllegalArgumentException(
                    "relation must not be null"
            );
        }
    }

    public String relationKey() {
        return relationKey(relation);
    }

    public static String relationKey(
            NormalizedRelationFact relation
    ) {
        if (relation == null) {
            return "";
        }

        return String.join(
                "|",
                safe(normalize(relation.kind())),
                safe(relation.srcSymbol()),
                safe(relation.dstSymbol()),
                safe(relation.dstRawRef())
        );
    }

    private static String normalize(String value) {
        return value == null
                ? null
                : value.trim().toLowerCase();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
