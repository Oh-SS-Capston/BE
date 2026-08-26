package com.example.ossdoc.domain.graphstore.model.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationPromotionCandidateParityReportTest {

    @Test
    @DisplayName("Candidate parity report 컬렉션은 외부에서 변경할 수 없다")
    void collectionsAreImmutable() {
        ObservationPromotionCandidateParityIssue issue =
                new ObservationPromotionCandidateParityIssue(
                        "publishes_event|source|target|",
                        ObservationPromotionCandidateParityStatus.MATCHED,
                        0,
                        "event_publication",
                        List.of()
                );

        ObservationPromotionCandidateParityReport report =
                new ObservationPromotionCandidateParityReport(
                        1,
                        1,
                        List.of(issue)
                );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.issues().add(issue)
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.counts().put(
                        ObservationPromotionCandidateParityStatus.MATCHED,
                        2L
                )
        );

        assertEquals(1, report.matchedCount());
        assertEquals(0, report.mismatchCount());
    }
}
