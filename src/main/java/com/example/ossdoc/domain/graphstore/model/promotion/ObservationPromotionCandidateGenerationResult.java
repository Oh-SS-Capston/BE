package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.List;

/**
 * Endpoint·Event·SPI shadow 후보 생성 결과.
 */
public record ObservationPromotionCandidateGenerationResult(
        int eligibleObservationCount,
        List<ObservationPromotionShadowCandidate> candidates,
        List<String> warnings
) {

    public ObservationPromotionCandidateGenerationResult {
        candidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }
}
