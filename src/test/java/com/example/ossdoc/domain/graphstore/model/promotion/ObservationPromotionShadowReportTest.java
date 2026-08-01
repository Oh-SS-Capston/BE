package com.example.ossdoc.domain.graphstore.model.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationPromotionShadowReportTest {

    @Test
    @DisplayName("Report와 Issue의 컬렉션은 외부에서 변경할 수 없다")
    void reportCollectionsAreImmutable() {
        ObservationPromotionShadowIssue issue =
                new ObservationPromotionShadowIssue(
                        0,
                        "event_publication",
                        "method:sample.A#run()",
                        "type:sample.Event",
                        ObservationPromotionShadowStatus.MATCHED,
                        "publishes_event",
                        "method:sample.A#run()",
                        "type:sample.Event",
                        List.of()
                );

        ObservationPromotionShadowReport report =
                new ObservationPromotionShadowReport(
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
                        ObservationPromotionShadowStatus.MATCHED,
                        99L
                )
        );

        assertEquals(1, report.matchedCount());
        assertEquals(0, report.mismatchCount());
    }
}
