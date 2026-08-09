package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * facts.json 전체에 대한 Observation 승격 shadow 분석 결과.
 */
public record ObservationPromotionShadowReport(
        int totalObservations,
        int promotableObservations,
        List<ObservationPromotionShadowIssue> issues
) {

    public ObservationPromotionShadowReport {
        issues = issues == null
                ? List.of()
                : List.copyOf(issues);
    }

    public long matchedCount() {
        return count(
                ObservationPromotionShadowStatus.MATCHED
        );
    }

    public long mismatchCount() {
        return issues.stream()
                .filter(ObservationPromotionShadowIssue::isMismatch)
                .count();
    }

    public boolean hasMismatches() {
        return mismatchCount() > 0;
    }

    public Map<ObservationPromotionShadowStatus, Long> counts() {
        EnumMap<ObservationPromotionShadowStatus, Long> counts =
                new EnumMap<>(
                        ObservationPromotionShadowStatus.class
                );

        for (ObservationPromotionShadowStatus status
                : ObservationPromotionShadowStatus.values()) {
            counts.put(status, 0L);
        }

        for (ObservationPromotionShadowIssue issue : issues) {
            if (issue == null || issue.status() == null) {
                continue;
            }

            counts.compute(
                    issue.status(),
                    (ignored, count) ->
                            count == null ? 1L : count + 1L
            );
        }

        return Collections.unmodifiableMap(counts);
    }

    private long count(
            ObservationPromotionShadowStatus status
    ) {
        return issues.stream()
                .filter(issue ->
                        issue != null
                                && issue.status() == status
                )
                .count();
    }
}
