package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extraction Observation resolver와 GraphStore shadow promoter가
 * 공동으로 따라야 하는 중립 승격 계약.
 *
 * Extraction enum에 의존하지 않고 JSON code 문자열을 사용한다.
 */
public record ObservationPromotionContract(
        String observationKind,
        Set<String> relationKinds,
        Set<String> semanticKinds,
        String resolverClassName,
        String derivation,
        ObservationEvidencePolicy evidencePolicy,
        boolean relationKindSelectedDynamically,
        Set<String> requiredRelationAttrs
) {

    public ObservationPromotionContract {
        observationKind = requireText(
                observationKind,
                "observationKind"
        );

        relationKinds = immutableTextSet(
                relationKinds,
                "relationKinds"
        );

        semanticKinds = immutableTextSet(
                semanticKinds,
                "semanticKinds"
        );

        resolverClassName = requireText(
                resolverClassName,
                "resolverClassName"
        );

        derivation = requireText(
                derivation,
                "derivation"
        );

        if (evidencePolicy == null) {
            throw new IllegalArgumentException(
                    "evidencePolicy must not be null"
            );
        }

        requiredRelationAttrs = immutableTextSet(
                requiredRelationAttrs,
                "requiredRelationAttrs"
        );
    }

    private static Set<String> immutableTextSet(
            Set<String> values,
            String field
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be empty"
            );
        }

        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String value : values) {
            normalized.add(
                    requireText(value, field)
            );
        }

        return Collections.unmodifiableSet(
                normalized
        );
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }
}
