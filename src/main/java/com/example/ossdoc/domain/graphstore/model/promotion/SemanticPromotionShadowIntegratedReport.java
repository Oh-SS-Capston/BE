package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observation 승격 계약과 모든 의미 Relation shadow 후보의
 * exact parity 결과를 하나로 합친 통합 리포트.
 */
public record SemanticPromotionShadowIntegratedReport(
        ObservationPromotionShadowReport contractReport,
        List<SemanticPromotionShadowStageReport> stages
) {

    private static final Set<SemanticPromotionShadowStage>
            REQUIRED_STAGES =
            Set.of(
                    SemanticPromotionShadowStage
                            .ENDPOINT_EVENT_SPI,
                    SemanticPromotionShadowStage
                            .BEAN_CONFIGURATION,
                    SemanticPromotionShadowStage
                            .REFLECTION,
                    SemanticPromotionShadowStage
                            .DI
            );

    public SemanticPromotionShadowIntegratedReport {
        contractReport = contractReport == null
                ? new ObservationPromotionShadowReport(
                        0,
                        0,
                        List.of()
                )
                : contractReport;

        stages = normalizeStages(stages);
    }

    public int totalObservations() {
        return contractReport.totalObservations();
    }

    public int promotableObservations() {
        return contractReport.promotableObservations();
    }

    public long contractMatchedCount() {
        return contractReport.matchedCount();
    }

    public long contractMismatchCount() {
        return contractReport.mismatchCount();
    }

    public int eligibleObservationCount() {
        return stages.stream()
                .mapToInt(
                        SemanticPromotionShadowStageReport
                                ::eligibleObservationCount
                )
                .sum();
    }

    public int generatedCandidateCount() {
        return stages.stream()
                .mapToInt(
                        SemanticPromotionShadowStageReport
                                ::generatedCandidateCount
                )
                .sum();
    }

    public int extractionRelationCount() {
        return stages.stream()
                .mapToInt(
                        SemanticPromotionShadowStageReport
                                ::extractionRelationCount
                )
                .sum();
    }

    public long exactMatchedCount() {
        return stages.stream()
                .mapToLong(
                        SemanticPromotionShadowStageReport
                                ::matchedCount
                )
                .sum();
    }

    public long exactMismatchCount() {
        return stages.stream()
                .mapToLong(
                        SemanticPromotionShadowStageReport
                                ::mismatchCount
                )
                .sum();
    }

    public int generationWarningCount() {
        return stages.stream()
                .mapToInt(
                        SemanticPromotionShadowStageReport
                                ::warningCount
                )
                .sum();
    }

    public long mismatchedStageCount() {
        return stages.stream()
                .filter(stage ->
                        !stage.isParityMatched()
                )
                .count();
    }

    public boolean hasContractMismatches() {
        return contractReport.hasMismatches();
    }

    public boolean hasExactParityMismatches() {
        return stages.stream()
                .anyMatch(
                        SemanticPromotionShadowStageReport
                                ::hasMismatches
                );
    }

    public boolean hasGenerationWarnings() {
        return stages.stream()
                .anyMatch(
                        SemanticPromotionShadowStageReport
                                ::hasWarnings
                );
    }

    public boolean hasMismatches() {
        return hasContractMismatches()
                || hasExactParityMismatches()
                || hasGenerationWarnings();
    }

    /**
     * GraphStore가 기존 Extraction 의미 Relation 생성 책임을 인수하기 위한
     * shadow 검증의 최소 통과 조건.
     *
     * 실제 저장 전환은 별도 단계에서 명시적으로 수행해야 한다.
     */
    public boolean isPersistencePromotionReady() {
        return eligibleObservationCount() > 0
                && !hasMismatches()
                && generatedCandidateCount()
                == extractionRelationCount()
                && exactMatchedCount()
                == generatedCandidateCount()
                && mismatchedStageCount() == 0;
    }

    public Map<
            ObservationPromotionCandidateParityStatus,
            Long
            > candidateCounts() {
        EnumMap<
                ObservationPromotionCandidateParityStatus,
                Long
                > result =
                new EnumMap<>(
                        ObservationPromotionCandidateParityStatus
                                .class
                );

        for (ObservationPromotionCandidateParityStatus status
                : ObservationPromotionCandidateParityStatus
                .values()) {
            result.put(status, 0L);
        }

        for (SemanticPromotionShadowStageReport stage
                : stages) {
            for (Map.Entry<
                    ObservationPromotionCandidateParityStatus,
                    Long
                    > entry
                    : stage.counts().entrySet()) {
                result.compute(
                        entry.getKey(),
                        (ignored, current) ->
                                (current == null ? 0L : current)
                                        + (entry.getValue() == null
                                        ? 0L
                                        : entry.getValue())
                );
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public Map<
            ObservationPromotionShadowStatus,
            Long
            > contractCounts() {
        return contractReport.counts();
    }

    public List<String> stageSummaries() {
        return stages.stream()
                .map(
                        SemanticPromotionShadowStageReport
                                ::summary
                )
                .toList();
    }

    public List<String> mismatchSamples(
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        List<String> result =
                new ArrayList<>();

        contractReport.issues().stream()
                .filter(
                        ObservationPromotionShadowIssue
                                ::isMismatch
                )
                .limit(limit)
                .map(issue ->
                        "contract: "
                                + issue.summary()
                )
                .forEach(result::add);

        if (result.size() >= limit) {
            return List.copyOf(result);
        }

        for (SemanticPromotionShadowStageReport stage
                : stages) {
            int remaining =
                    limit - result.size();

            if (remaining <= 0) {
                break;
            }

            result.addAll(
                    stage.mismatchSamples(
                            remaining
                    )
            );
        }

        return List.copyOf(result);
    }

    public List<String> warningSamples(
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        List<String> result =
                new ArrayList<>();

        for (SemanticPromotionShadowStageReport stage
                : stages) {
            int remaining =
                    limit - result.size();

            if (remaining <= 0) {
                break;
            }

            result.addAll(
                    stage.warningSamples(
                            remaining
                    )
            );
        }

        return List.copyOf(result);
    }

    private static List<SemanticPromotionShadowStageReport>
    normalizeStages(
            List<SemanticPromotionShadowStageReport> rawStages
    ) {
        EnumMap<
                SemanticPromotionShadowStage,
                SemanticPromotionShadowStageReport
                > byStage =
                new EnumMap<>(
                        SemanticPromotionShadowStage.class
                );

        if (rawStages != null) {
            for (SemanticPromotionShadowStageReport stage
                    : rawStages) {
                if (stage == null) {
                    continue;
                }

                SemanticPromotionShadowStageReport previous =
                        byStage.putIfAbsent(
                                stage.stage(),
                                stage
                        );

                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate semantic shadow stage: "
                                    + stage.stage()
                    );
                }
            }
        }

        LinkedHashSet<SemanticPromotionShadowStage> missing =
                new LinkedHashSet<>(REQUIRED_STAGES);

        missing.removeAll(byStage.keySet());

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing semantic shadow stages: "
                            + missing
            );
        }

        List<SemanticPromotionShadowStageReport> ordered =
                new ArrayList<>();

        for (SemanticPromotionShadowStage stage
                : SemanticPromotionShadowStage.values()) {
            SemanticPromotionShadowStageReport report =
                    byStage.get(stage);

            if (report != null) {
                ordered.add(report);
            }
        }

        return List.copyOf(ordered);
    }
}
