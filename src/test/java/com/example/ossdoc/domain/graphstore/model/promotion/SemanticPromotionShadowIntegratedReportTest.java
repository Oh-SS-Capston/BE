package com.example.ossdoc.domain.graphstore.model.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPromotionShadowIntegratedReportTest {

    @Test
    @DisplayName("네 shadow 단계가 모두 exact match이면 책임 이전 준비 상태다")
    void readyWhenEveryStageMatches() {
        SemanticPromotionShadowIntegratedReport report =
                SemanticPromotionShadowIntegratedReportFactory
                        .create(
                                contractReport(
                                        List.of(
                                                contractIssue(
                                                        ObservationPromotionShadowStatus
                                                                .MATCHED
                                                )
                                        )
                                ),
                                generation(1),
                                parity(
                                        1,
                                        1,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MATCHED
                                        )
                                ),
                                generation(2),
                                parity(
                                        2,
                                        2,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MATCHED
                                        ),
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MATCHED
                                        )
                                ),
                                generation(1),
                                parity(
                                        1,
                                        1,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MATCHED
                                        )
                                ),
                                generation(1),
                                parity(
                                        1,
                                        1,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MATCHED
                                        )
                                )
                        );

        assertEquals(5, report.eligibleObservationCount());
        assertEquals(5, report.generatedCandidateCount());
        assertEquals(5, report.extractionRelationCount());
        assertEquals(5, report.exactMatchedCount());
        assertEquals(0, report.exactMismatchCount());
        assertEquals(0, report.generationWarningCount());
        assertEquals(0, report.mismatchedStageCount());

        assertEquals(
                5L,
                report.candidateCounts().get(
                        ObservationPromotionCandidateParityStatus
                                .MATCHED
                )
        );

        assertTrue(
                report.isPersistencePromotionReady()
        );
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("계약·candidate·warning 문제를 모두 통합해서 집계한다")
    void aggregatesAllIssueTypes() {
        ObservationPromotionShadowIssue contractMismatch =
                new ObservationPromotionShadowIssue(
                        0,
                        "event_publication",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        ObservationPromotionShadowStatus
                                .METADATA_MISMATCH,
                        "publishes_event",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        List.of("resolver mismatch")
                );

        SemanticPromotionShadowIntegratedReport report =
                SemanticPromotionShadowIntegratedReportFactory
                        .create(
                                new ObservationPromotionShadowReport(
                                        2,
                                        2,
                                        List.of(
                                                contractIssue(
                                                        ObservationPromotionShadowStatus
                                                                .MATCHED
                                                ),
                                                contractMismatch
                                        )
                                ),
                                new ObservationPromotionCandidateGenerationResult(
                                        1,
                                        List.of(),
                                        List.of(
                                                "endpoint generation warning"
                                        )
                                ),
                                parity(
                                        1,
                                        1,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .METADATA_MISMATCH
                                        )
                                ),
                                generation(1),
                                parity(
                                        1,
                                        0,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .MISSING_EXTRACTION_RELATION
                                        )
                                ),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(
                                        0,
                                        1,
                                        parityIssue(
                                                ObservationPromotionCandidateParityStatus
                                                        .EXTRACTION_ONLY
                                        )
                                )
                        );

        assertTrue(report.hasContractMismatches());
        assertTrue(report.hasExactParityMismatches());
        assertTrue(report.hasGenerationWarnings());
        assertTrue(report.hasMismatches());
        assertFalse(
                report.isPersistencePromotionReady()
        );

        assertEquals(1, report.contractMismatchCount());
        assertEquals(3, report.exactMismatchCount());
        assertEquals(1, report.generationWarningCount());
        assertEquals(3, report.mismatchedStageCount());

        assertEquals(
                1L,
                report.candidateCounts().get(
                        ObservationPromotionCandidateParityStatus
                                .METADATA_MISMATCH
                )
        );
        assertEquals(
                1L,
                report.candidateCounts().get(
                        ObservationPromotionCandidateParityStatus
                                .MISSING_EXTRACTION_RELATION
                )
        );
        assertEquals(
                1L,
                report.candidateCounts().get(
                        ObservationPromotionCandidateParityStatus
                                .EXTRACTION_ONLY
                )
        );

        assertTrue(
                report.mismatchSamples(10)
                        .stream()
                        .anyMatch(sample ->
                                sample.startsWith(
                                        "contract:"
                                )
                        )
        );

        assertEquals(
                List.of(
                        "endpoint_event_spi: endpoint generation warning"
                ),
                report.warningSamples(10)
        );
    }

    @Test
    @DisplayName("대상 Observation이 없으면 mismatch가 없어도 책임 이전 준비 상태는 아니다")
    void emptyReportIsNotReady() {
        SemanticPromotionShadowIntegratedReport report =
                SemanticPromotionShadowIntegratedReportFactory
                        .create(
                                contractReport(List.of()),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0)
                        );

        assertFalse(report.hasMismatches());
        assertFalse(
                report.isPersistencePromotionReady()
        );
    }

    @Test
    @DisplayName("통합 리포트는 네 단계를 고정 순서로 보존하며 외부 변경을 허용하지 않는다")
    void stagesAreOrderedAndImmutable() {
        SemanticPromotionShadowIntegratedReport report =
                SemanticPromotionShadowIntegratedReportFactory
                        .create(
                                contractReport(List.of()),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0),
                                generation(0),
                                parity(0, 0)
                        );

        assertEquals(
                List.of(
                        SemanticPromotionShadowStage
                                .ENDPOINT_EVENT_SPI,
                        SemanticPromotionShadowStage
                                .BEAN_CONFIGURATION,
                        SemanticPromotionShadowStage
                                .REFLECTION,
                        SemanticPromotionShadowStage
                                .DI
                ),
                report.stages().stream()
                        .map(
                                SemanticPromotionShadowStageReport
                                        ::stage
                        )
                        .toList()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.stages().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.candidateCounts().put(
                        ObservationPromotionCandidateParityStatus
                                .MATCHED,
                        99L
                )
        );
    }

    @Test
    @DisplayName("단계 누락 또는 중복은 통합 리포트 생성 시 거부한다")
    void rejectsMissingOrDuplicateStages() {
        SemanticPromotionShadowStageReport endpoint =
                stage(
                        SemanticPromotionShadowStage
                                .ENDPOINT_EVENT_SPI
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticPromotionShadowIntegratedReport(
                        contractReport(List.of()),
                        List.of(endpoint)
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticPromotionShadowIntegratedReport(
                        contractReport(List.of()),
                        List.of(
                                endpoint,
                                endpoint,
                                stage(
                                        SemanticPromotionShadowStage
                                                .BEAN_CONFIGURATION
                                ),
                                stage(
                                        SemanticPromotionShadowStage
                                                .REFLECTION
                                ),
                                stage(
                                        SemanticPromotionShadowStage
                                                .DI
                                )
                        )
                )
        );
    }

    private SemanticPromotionShadowStageReport stage(
            SemanticPromotionShadowStage stage
    ) {
        return new SemanticPromotionShadowStageReport(
                stage,
                generation(0),
                parity(0, 0)
        );
    }

    private ObservationPromotionShadowReport contractReport(
            List<ObservationPromotionShadowIssue> issues
    ) {
        return new ObservationPromotionShadowReport(
                issues.size(),
                issues.size(),
                issues
        );
    }

    private ObservationPromotionShadowIssue contractIssue(
            ObservationPromotionShadowStatus status
    ) {
        return new ObservationPromotionShadowIssue(
                0,
                "event_publication",
                "method:sample.Service#publish()",
                "type:sample.Event",
                status,
                "publishes_event",
                "method:sample.Service#publish()",
                "type:sample.Event",
                List.of()
        );
    }

    private ObservationPromotionCandidateGenerationResult generation(
            int eligible
    ) {
        return new ObservationPromotionCandidateGenerationResult(
                eligible,
                List.of(),
                List.of()
        );
    }

    private ObservationPromotionCandidateParityReport parity(
            int generated,
            int extraction,
            ObservationPromotionCandidateParityIssue... issues
    ) {
        return new ObservationPromotionCandidateParityReport(
                generated,
                extraction,
                issues == null
                        ? List.of()
                        : List.of(issues)
        );
    }

    private ObservationPromotionCandidateParityIssue parityIssue(
            ObservationPromotionCandidateParityStatus status
    ) {
        return new ObservationPromotionCandidateParityIssue(
                "kind|src|dst|raw",
                status,
                0,
                "event_publication",
                List.of(
                        status == ObservationPromotionCandidateParityStatus
                                .MATCHED
                                ? ""
                                : "fixture mismatch"
                )
        );
    }
}
