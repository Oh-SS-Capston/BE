package com.example.ossdoc.domain.graphstore.service.promotion.gate;

import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityStatus;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowStatus;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowIntegratedReport;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStage;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStageReport;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateDecision;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateScope;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPromotionResponsibilityGateTest {

    private SemanticPromotionGateProperties properties;
    private SemanticPromotionResponsibilityGate gate;

    @BeforeEach
    void setUp() {
        properties =
                new SemanticPromotionGateProperties();

        properties.setRequiredConsecutiveSuccesses(3);
        properties.setMinimumMatchedCandidatesPerRun(1);
        properties.setMinimumCumulativeMatchedCandidates(12);
        properties.setRequiredDistinctRepositories(2);
        properties.setRequiredStages(
                EnumSet.allOf(
                        SemanticPromotionShadowStage.class
                )
        );

        gate =
                new SemanticPromotionResponsibilityGate(
                        properties
                );
    }

    @Test
    @DisplayName("서로 다른 실제 저장소의 3회 연속 성공과 전체 단계 커버리지 후 준비 상태가 된다")
    void becomesReadyAfterSafeValidationWindow() {
        SemanticPromotionShadowIntegratedReport report =
                readyReport(
                        EnumSet.allOf(
                                SemanticPromotionShadowStage.class
                        )
                );

        SemanticPromotionGateDecision first =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a.git",
                        report
                );

        SemanticPromotionGateDecision second =
                gate.evaluate(
                        "run-2",
                        "https://github.com/example/repo-b/",
                        report
                );

        SemanticPromotionGateDecision third =
                gate.evaluate(
                        "run-3",
                        "https://github.com/example/repo-a",
                        report
                );

        assertEquals(
                SemanticPromotionGateStatus.WARMING_UP,
                first.status()
        );
        assertEquals(
                SemanticPromotionGateStatus.WARMING_UP,
                second.status()
        );
        assertEquals(
                SemanticPromotionGateStatus
                        .READY_BUT_PERSISTENCE_DISABLED,
                third.status()
        );

        assertEquals(3, third.consecutiveSuccesses());
        assertEquals(12, third.cumulativeMatchedCandidates());
        assertEquals(2, third.distinctRepositories());
        assertTrue(third.ready());
        assertFalse(third.candidatePersistencePermitted());
    }

    @Test
    @DisplayName("중간 실패는 연속 성공·누적 candidate·단계 커버리지를 초기화한다")
    void failureResetsValidationWindow() {
        SemanticPromotionShadowIntegratedReport ready =
                readyReport(
                        EnumSet.allOf(
                                SemanticPromotionShadowStage.class
                        )
                );

        gate.evaluate(
                "run-1",
                "https://github.com/example/repo-a",
                ready
        );

        SemanticPromotionGateDecision blocked =
                gate.evaluate(
                        "run-2",
                        "https://github.com/example/repo-b",
                        notReadyReport()
                );

        assertEquals(
                SemanticPromotionGateStatus.BLOCKED,
                blocked.status()
        );
        assertEquals(0, blocked.consecutiveSuccesses());
        assertEquals(0, blocked.cumulativeMatchedCandidates());
        assertTrue(blocked.coveredStages().isEmpty());
        assertTrue(blocked.blocked());

        SemanticPromotionGateDecision restarted =
                gate.evaluate(
                        "run-3",
                        "https://github.com/example/repo-a",
                        ready
                );

        assertEquals(
                1,
                restarted.consecutiveSuccesses()
        );
        assertEquals(
                SemanticPromotionGateStatus.WARMING_UP,
                restarted.status()
        );
    }

    @Test
    @DisplayName("동일 runId 재평가는 성공 이력을 중복 증가시키지 않는다")
    void duplicateRunDoesNotAdvanceWindow() {
        SemanticPromotionShadowIntegratedReport report =
                readyReport(
                        EnumSet.allOf(
                                SemanticPromotionShadowStage.class
                        )
                );

        SemanticPromotionGateDecision first =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a",
                        report
                );

        SemanticPromotionGateDecision duplicate =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a",
                        report
                );

        assertEquals(
                SemanticPromotionGateStatus
                        .DUPLICATE_RUN_IGNORED,
                duplicate.status()
        );
        assertEquals(
                first.consecutiveSuccesses(),
                duplicate.consecutiveSuccesses()
        );
        assertEquals(
                first.cumulativeMatchedCandidates(),
                duplicate.cumulativeMatchedCandidates()
        );
    }

    @Test
    @DisplayName("필수 단계 중 하나라도 실제 데이터에서 커버되지 않으면 준비 상태가 되지 않는다")
    void missingStageCoverageKeepsGateWarming() {
        SemanticPromotionShadowIntegratedReport report =
                readyReport(
                        EnumSet.of(
                                SemanticPromotionShadowStage
                                        .ENDPOINT_EVENT_SPI,
                                SemanticPromotionShadowStage
                                        .BEAN_CONFIGURATION,
                                SemanticPromotionShadowStage
                                        .DI
                        )
                );

        SemanticPromotionGateDecision decision = null;

        for (int index = 1; index <= 3; index++) {
            decision = gate.evaluate(
                    "run-" + index,
                    index == 2
                            ? "https://github.com/example/repo-b"
                            : "https://github.com/example/repo-a",
                    report
            );
        }

        assertEquals(
                SemanticPromotionGateStatus.WARMING_UP,
                decision.status()
        );
        assertFalse(
                decision.coveredStages().contains(
                        SemanticPromotionShadowStage
                                .REFLECTION
                )
        );
        assertTrue(
                decision.reasons().stream()
                        .anyMatch(reason ->
                                reason.contains(
                                        "required stage coverage"
                                )
                        )
        );
    }

    @Test
    @DisplayName("candidate persistence 플래그가 활성화된 경우에만 Gate가 저장 허용 상태를 반환한다")
    void persistenceFlagControlsPermission() {
        properties.setRequiredConsecutiveSuccesses(1);
        properties.setMinimumCumulativeMatchedCandidates(4);
        properties.setRequiredDistinctRepositories(1);
        properties.setCandidatePersistenceEnabled(true);

        SemanticPromotionGateDecision decision =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a",
                        readyReport(
                                EnumSet.allOf(
                                        SemanticPromotionShadowStage.class
                                )
                        )
                );

        assertEquals(
                SemanticPromotionGateStatus.READY,
                decision.status()
        );
        assertTrue(
                decision.candidatePersistenceFlagEnabled()
        );
        assertTrue(
                decision.candidatePersistencePermitted()
        );
    }

    @Test
    @DisplayName("REPOSITORY scope에서는 저장소별로 독립 이력을 유지한다")
    void repositoryScopeKeepsIndependentWindows() {
        properties.setScope(
                SemanticPromotionGateScope.REPOSITORY
        );
        properties.setRequiredConsecutiveSuccesses(2);
        properties.setMinimumCumulativeMatchedCandidates(8);
        properties.setRequiredDistinctRepositories(99);

        SemanticPromotionShadowIntegratedReport report =
                readyReport(
                        EnumSet.allOf(
                                SemanticPromotionShadowStage.class
                        )
                );

        SemanticPromotionGateDecision repoAFirst =
                gate.evaluate(
                        "run-a1",
                        "https://github.com/example/repo-a",
                        report
                );

        SemanticPromotionGateDecision repoBFirst =
                gate.evaluate(
                        "run-b1",
                        "https://github.com/example/repo-b",
                        report
                );

        SemanticPromotionGateDecision repoASecond =
                gate.evaluate(
                        "run-a2",
                        "https://github.com/example/repo-a.git",
                        report
                );

        assertEquals(
                1,
                repoAFirst.consecutiveSuccesses()
        );
        assertEquals(
                1,
                repoBFirst.consecutiveSuccesses()
        );
        assertEquals(
                2,
                repoASecond.consecutiveSuccesses()
        );
        assertEquals(
                SemanticPromotionGateStatus
                        .READY_BUT_PERSISTENCE_DISABLED,
                repoASecond.status()
        );
        assertEquals(
                1,
                repoASecond.requiredDistinctRepositories()
        );
    }

    @Test
    @DisplayName("Gate 비활성화 상태는 이력을 생성하지 않는다")
    void disabledGateDoesNotTrackHistory() {
        properties.setEnabled(false);

        SemanticPromotionGateDecision disabled =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a",
                        readyReport(
                                EnumSet.allOf(
                                        SemanticPromotionShadowStage.class
                                )
                        )
                );

        assertEquals(
                SemanticPromotionGateStatus.DISABLED,
                disabled.status()
        );
        assertEquals(0, disabled.consecutiveSuccesses());

        properties.setEnabled(true);

        SemanticPromotionGateDecision first =
                gate.evaluate(
                        "run-1",
                        "https://github.com/example/repo-a",
                        readyReport(
                                EnumSet.allOf(
                                        SemanticPromotionShadowStage.class
                                )
                        )
                );

        assertEquals(1, first.consecutiveSuccesses());
    }

    private SemanticPromotionShadowIntegratedReport readyReport(
            Set<SemanticPromotionShadowStage> coveredStages
    ) {
        List<SemanticPromotionShadowStageReport> stages =
                new ArrayList<>();

        List<ObservationPromotionShadowIssue> contractIssues =
                new ArrayList<>();

        int observationIndex = 0;

        for (SemanticPromotionShadowStage stage
                : SemanticPromotionShadowStage.values()) {

            boolean covered =
                    coveredStages.contains(stage);

            int count = covered ? 1 : 0;

            List<ObservationPromotionCandidateParityIssue>
                    parityIssues =
                    covered
                            ? List.of(
                            new ObservationPromotionCandidateParityIssue(
                                    stage.code()
                                            + "|src|dst|",
                                    ObservationPromotionCandidateParityStatus
                                            .MATCHED,
                                    observationIndex,
                                    stage.code(),
                                    List.of()
                            )
                    )
                            : List.of();

            stages.add(
                    new SemanticPromotionShadowStageReport(
                            stage,
                            new ObservationPromotionCandidateGenerationResult(
                                    count,
                                    List.of(),
                                    List.of()
                            ),
                            new ObservationPromotionCandidateParityReport(
                                    count,
                                    count,
                                    parityIssues
                            )
                    )
            );

            if (covered) {
                contractIssues.add(
                        new ObservationPromotionShadowIssue(
                                observationIndex,
                                stage.code(),
                                "symbol:source",
                                "symbol:target",
                                ObservationPromotionShadowStatus
                                        .MATCHED,
                                "relation_kind",
                                "symbol:source",
                                "symbol:target",
                                List.of()
                        )
                );

                observationIndex++;
            }
        }

        return new SemanticPromotionShadowIntegratedReport(
                new ObservationPromotionShadowReport(
                        contractIssues.size(),
                        contractIssues.size(),
                        contractIssues
                ),
                stages
        );
    }

    private SemanticPromotionShadowIntegratedReport
    notReadyReport() {
        List<SemanticPromotionShadowStageReport> stages =
                new ArrayList<>();

        for (SemanticPromotionShadowStage stage
                : SemanticPromotionShadowStage.values()) {
            stages.add(
                    new SemanticPromotionShadowStageReport(
                            stage,
                            new ObservationPromotionCandidateGenerationResult(
                                    1,
                                    List.of(),
                                    List.of()
                            ),
                            new ObservationPromotionCandidateParityReport(
                                    1,
                                    0,
                                    List.of(
                                            new ObservationPromotionCandidateParityIssue(
                                                    stage.code()
                                                            + "|src|dst|",
                                                    ObservationPromotionCandidateParityStatus
                                                            .MISSING_EXTRACTION_RELATION,
                                                    0,
                                                    stage.code(),
                                                    List.of(
                                                            "fixture mismatch"
                                                    )
                                            )
                                    )
                            )
                    )
            );
        }

        return new SemanticPromotionShadowIntegratedReport(
                new ObservationPromotionShadowReport(
                        1,
                        1,
                        List.of(
                                new ObservationPromotionShadowIssue(
                                        0,
                                        "event_publication",
                                        "symbol:source",
                                        "symbol:target",
                                        ObservationPromotionShadowStatus
                                                .MISSING_RELATION,
                                        null,
                                        null,
                                        null,
                                        List.of(
                                                "fixture mismatch"
                                        )
                                )
                        )
                ),
                stages
        );
    }
}
