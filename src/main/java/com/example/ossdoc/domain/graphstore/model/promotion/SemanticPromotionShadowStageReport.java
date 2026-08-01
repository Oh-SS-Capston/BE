package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.List;
import java.util.Map;

/**
 * 하나의 의미 Relation shadow 단계에 대한 생성·비교 결과.
 */
public record SemanticPromotionShadowStageReport(
        SemanticPromotionShadowStage stage,
        ObservationPromotionCandidateGenerationResult generation,
        ObservationPromotionCandidateParityReport parity
) {

    public SemanticPromotionShadowStageReport {
        if (stage == null) {
            throw new IllegalArgumentException(
                    "stage must not be null"
            );
        }

        generation = generation == null
                ? new ObservationPromotionCandidateGenerationResult(
                        0,
                        List.of(),
                        List.of()
                )
                : generation;

        parity = parity == null
                ? new ObservationPromotionCandidateParityReport(
                        0,
                        0,
                        List.of()
                )
                : parity;
    }

    public int eligibleObservationCount() {
        return generation.eligibleObservationCount();
    }

    public int generatedCandidateCount() {
        return parity.generatedCandidateCount();
    }

    public int extractionRelationCount() {
        return parity.extractionRelationCount();
    }

    public long matchedCount() {
        return parity.matchedCount();
    }

    public long mismatchCount() {
        return parity.mismatchCount();
    }

    public int warningCount() {
        return generation.warnings().size();
    }

    public boolean hasMismatches() {
        return parity.hasMismatches();
    }

    public boolean hasWarnings() {
        return !generation.warnings().isEmpty();
    }

    /**
     * 대상 Observation과 Extraction Relation이 모두 없는 단계만
     * 중립적인 통과로 본다.
     *
     * 대상 Observation은 없지만 Extraction Relation만 존재하는
     * EXTRACTION_ONLY 상태는 불일치로 유지해야 한다.
     */
    public boolean isParityMatched() {
        return !hasMismatches()
                && !hasWarnings()
                && generatedCandidateCount()
                == extractionRelationCount()
                && matchedCount()
                == generatedCandidateCount();
    }

    public Map<
            ObservationPromotionCandidateParityStatus,
            Long
            > counts() {
        return parity.counts();
    }

    public List<String> mismatchSamples(
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        return parity.issues().stream()
                .filter(
                        ObservationPromotionCandidateParityIssue
                                ::isMismatch
                )
                .limit(limit)
                .map(issue ->
                        stage.code()
                                + ": "
                                + issue.summary()
                )
                .toList();
    }

    public List<String> warningSamples(
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        return generation.warnings().stream()
                .limit(limit)
                .map(warning ->
                        stage.code()
                                + ": "
                                + warning
                )
                .toList();
    }

    public String summary() {
        return "stage="
                + stage.code()
                + ", eligible="
                + eligibleObservationCount()
                + ", generated="
                + generatedCandidateCount()
                + ", extraction="
                + extractionRelationCount()
                + ", matched="
                + matchedCount()
                + ", mismatches="
                + mismatchCount()
                + ", warnings="
                + warningCount()
                + ", counts="
                + counts();
    }
}
