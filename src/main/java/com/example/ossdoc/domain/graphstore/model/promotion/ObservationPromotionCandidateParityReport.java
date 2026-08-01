package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint·Event·SPI shadow 후보와 Extraction Relation의 parity report.
 */
public record ObservationPromotionCandidateParityReport(
        int generatedCandidateCount,
        int extractionRelationCount,
        List<ObservationPromotionCandidateParityIssue> issues
) {

    public ObservationPromotionCandidateParityReport {
        issues = issues == null
                ? List.of()
                : List.copyOf(issues);
    }

    public long matchedCount() {
        return count(
                ObservationPromotionCandidateParityStatus.MATCHED
        );
    }

    public long mismatchCount() {
        return issues.stream()
                .filter(ObservationPromotionCandidateParityIssue::isMismatch)
                .count();
    }

    public boolean hasMismatches() {
        return mismatchCount() > 0;
    }

    public Map<ObservationPromotionCandidateParityStatus, Long> counts() {
        EnumMap<ObservationPromotionCandidateParityStatus, Long> result =
                new EnumMap<>(
                        ObservationPromotionCandidateParityStatus.class
                );

        for (ObservationPromotionCandidateParityStatus status
                : ObservationPromotionCandidateParityStatus.values()) {
            result.put(status, 0L);
        }

        for (ObservationPromotionCandidateParityIssue issue : issues) {
            if (issue == null || issue.status() == null) {
                continue;
            }

            result.compute(
                    issue.status(),
                    (ignored, count) ->
                            count == null ? 1L : count + 1L
            );
        }

        return Collections.unmodifiableMap(result);
    }

    private long count(
            ObservationPromotionCandidateParityStatus status
    ) {
        return issues.stream()
                .filter(issue ->
                        issue != null
                                && issue.status() == status
                )
                .count();
    }
}
