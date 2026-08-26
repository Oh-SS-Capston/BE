package com.example.ossdoc.domain.graphstore.model.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPromotionShadowStageReportTest {

    @Test
    @DisplayName("대상 Observation이 없는 단계는 중립 통과다")
    void emptyStageIsNeutral() {
        SemanticPromotionShadowStageReport report =
                new SemanticPromotionShadowStageReport(
                        SemanticPromotionShadowStage
                                .REFLECTION,
                        null,
                        null
                );

        assertTrue(report.isParityMatched());
        assertFalse(report.hasMismatches());
        assertFalse(report.hasWarnings());
    }

    @Test
    @DisplayName("warning만 있어도 단계 parity 통과로 보지 않는다")
    void warningPreventsStageMatch() {
        SemanticPromotionShadowStageReport report =
                new SemanticPromotionShadowStageReport(
                        SemanticPromotionShadowStage.DI,
                        new ObservationPromotionCandidateGenerationResult(
                                1,
                                List.of(),
                                List.of("owner type missing")
                        ),
                        new ObservationPromotionCandidateParityReport(
                                0,
                                0,
                                List.of()
                        )
                );

        assertFalse(report.isParityMatched());
        assertTrue(report.hasWarnings());
    }
}
