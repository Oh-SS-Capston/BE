package com.example.ossdoc.domain.graphstore.service.promotion.gate;

import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowIntegratedReport;
import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStage;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateDecision;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateScope;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 실제 OSS ingest 결과에서 의미 Relation Shadow parity가
 * 연속해서 안정적으로 유지되는지를 누적 평가한다.
 *
 * <p>현재 구현은 프로세스 메모리에만 이력을 보관한다.
 * 애플리케이션 재시작 시 이력은 초기화된다.</p>
 */
@Component
public class SemanticPromotionResponsibilityGate {

    private static final String GLOBAL_SCOPE_KEY =
            "global";

    private static final String UNKNOWN_REPOSITORY =
            "<unknown-repository>";

    private final SemanticPromotionGateProperties properties;

    private final Object monitor = new Object();

    private final Map<String, GateState> states =
            new LinkedHashMap<>();

    public SemanticPromotionResponsibilityGate(
            SemanticPromotionGateProperties properties
    ) {
        this.properties = properties;
    }

    public SemanticPromotionGateDecision evaluate(
            String runId,
            String repoUrl,
            SemanticPromotionShadowIntegratedReport report
    ) {
        String normalizedRunId =
                normalizeRunId(runId);

        String repositoryKey =
                normalizeRepositoryKey(repoUrl);

        if (!properties.isEnabled()) {
            return disabledDecision(
                    normalizedRunId,
                    repositoryKey
            );
        }

        String scopeKey =
                resolveScopeKey(repositoryKey);

        synchronized (monitor) {
            GateState state =
                    states.computeIfAbsent(
                            scopeKey,
                            ignored -> new GateState()
                    );

            if (state.processedRunIds
                    .contains(normalizedRunId)) {
                return decision(
                        SemanticPromotionGateStatus
                                .DUPLICATE_RUN_IGNORED,
                        scopeKey,
                        normalizedRunId,
                        repositoryKey,
                        report,
                        state,
                        List.of(
                                "runId was already evaluated; "
                                        + "gate history was not changed"
                        )
                );
            }

            state.rememberRun(
                    normalizedRunId,
                    properties.getMaxTrackedRunIds()
            );

            boolean reportReady =
                    report != null
                            && report
                            .isPersistencePromotionReady();

            int matchedCandidates =
                    report == null
                            ? 0
                            : Math.toIntExact(
                                    Math.min(
                                            Integer.MAX_VALUE,
                                            report
                                            .exactMatchedCount()
                                    )
                    );

            boolean minimumRunCandidatesMet =
                    matchedCandidates
                            >= properties
                            .getMinimumMatchedCandidatesPerRun();

            boolean currentRunSuccess =
                    reportReady
                            && minimumRunCandidatesMet;

            if (!currentRunSuccess) {
                state.resetSuccessWindow();

                List<String> reasons =
                        new ArrayList<>();

                if (!reportReady) {
                    reasons.add(
                            "integrated Shadow report is not "
                                    + "persistence-promotion ready"
                    );
                }

                if (!minimumRunCandidatesMet) {
                    reasons.add(
                            "current run matched candidates "
                                    + matchedCandidates
                                    + " < required "
                                    + properties
                                    .getMinimumMatchedCandidatesPerRun()
                    );
                }

                return decision(
                        SemanticPromotionGateStatus.BLOCKED,
                        scopeKey,
                        normalizedRunId,
                        repositoryKey,
                        report,
                        state,
                        reasons
                );
            }

            state.consecutiveSuccesses++;
            state.cumulativeMatchedCandidates +=
                    matchedCandidates;

            state.distinctRepositories.add(
                    repositoryKey
            );

            state.coveredStages.addAll(
                    coveredStages(report)
            );

            List<String> missingConditions =
                    missingConditions(state);

            if (!missingConditions.isEmpty()) {
                return decision(
                        SemanticPromotionGateStatus.WARMING_UP,
                        scopeKey,
                        normalizedRunId,
                        repositoryKey,
                        report,
                        state,
                        missingConditions
                );
            }

            boolean persistenceFlag =
                    properties
                            .isCandidatePersistenceEnabled();

            return decision(
                    persistenceFlag
                            ? SemanticPromotionGateStatus.READY
                            : SemanticPromotionGateStatus
                            .READY_BUT_PERSISTENCE_DISABLED,
                    scopeKey,
                    normalizedRunId,
                    repositoryKey,
                    report,
                    state,
                    persistenceFlag
                            ? List.of(
                            "all validation conditions passed; "
                                    + "candidate persistence flag is enabled"
                    )
                            : List.of(
                            "all validation conditions passed; "
                                    + "candidate persistence flag remains disabled"
                    )
            );
        }
    }

    /**
     * 테스트 및 운영상 명시적 초기화에 사용한다.
     * 호출하지 않는 한 성공 이력은 동일 프로세스에서 계속 누적된다.
     */
    public void reset() {
        synchronized (monitor) {
            states.clear();
        }
    }

    private SemanticPromotionGateDecision disabledDecision(
            String runId,
            String repositoryKey
    ) {
        return new SemanticPromotionGateDecision(
                SemanticPromotionGateStatus.DISABLED,
                resolveScopeKey(repositoryKey),
                runId,
                repositoryKey,
                false,
                0,
                0,
                properties
                        .getRequiredConsecutiveSuccesses(),
                0,
                properties
                        .getMinimumCumulativeMatchedCandidates(),
                0,
                effectiveRequiredDistinctRepositories(),
                Set.of(),
                properties.getRequiredStages(),
                properties
                        .isCandidatePersistenceEnabled(),
                false,
                List.of(
                        "semantic promotion gate is disabled"
                )
        );
    }

    private SemanticPromotionGateDecision decision(
            SemanticPromotionGateStatus status,
            String scopeKey,
            String runId,
            String repositoryKey,
            SemanticPromotionShadowIntegratedReport report,
            GateState state,
            List<String> reasons
    ) {
        boolean currentRunReady =
                report != null
                        && report
                        .isPersistencePromotionReady();

        int currentMatched =
                report == null
                        ? 0
                        : Math.toIntExact(
                                Math.min(
                                        Integer.MAX_VALUE,
                                        report.exactMatchedCount()
                                )
                );

        boolean flagEnabled =
                properties
                        .isCandidatePersistenceEnabled();

        boolean persistencePermitted =
                status == SemanticPromotionGateStatus.READY
                        && flagEnabled;

        return new SemanticPromotionGateDecision(
                status,
                scopeKey,
                runId,
                repositoryKey,
                currentRunReady,
                currentMatched,
                state.consecutiveSuccesses,
                properties
                        .getRequiredConsecutiveSuccesses(),
                state.cumulativeMatchedCandidates,
                properties
                        .getMinimumCumulativeMatchedCandidates(),
                state.distinctRepositories.size(),
                effectiveRequiredDistinctRepositories(),
                state.coveredStages,
                properties.getRequiredStages(),
                flagEnabled,
                persistencePermitted,
                reasons
        );
    }

    private List<String> missingConditions(
            GateState state
    ) {
        List<String> reasons =
                new ArrayList<>();

        if (state.consecutiveSuccesses
                < properties
                .getRequiredConsecutiveSuccesses()) {
            reasons.add(
                    "consecutive successes "
                            + state.consecutiveSuccesses
                            + " < required "
                            + properties
                            .getRequiredConsecutiveSuccesses()
            );
        }

        if (state.cumulativeMatchedCandidates
                < properties
                .getMinimumCumulativeMatchedCandidates()) {
            reasons.add(
                    "cumulative matched candidates "
                            + state
                            .cumulativeMatchedCandidates
                            + " < required "
                            + properties
                            .getMinimumCumulativeMatchedCandidates()
            );
        }

        int requiredRepositories =
                effectiveRequiredDistinctRepositories();

        if (state.distinctRepositories.size()
                < requiredRepositories) {
            reasons.add(
                    "distinct repositories "
                            + state
                            .distinctRepositories
                            .size()
                            + " < required "
                            + requiredRepositories
            );
        }

        Set<SemanticPromotionShadowStage> missingStages =
                EnumSet.copyOf(
                        properties.getRequiredStages()
                );

        missingStages.removeAll(
                state.coveredStages
        );

        if (!missingStages.isEmpty()) {
            reasons.add(
                    "required stage coverage is missing: "
                            + missingStages
            );
        }

        return List.copyOf(reasons);
    }

    private int effectiveRequiredDistinctRepositories() {
        return properties.getScope()
                == SemanticPromotionGateScope.REPOSITORY
                ? 1
                : properties
                .getRequiredDistinctRepositories();
    }

    private Set<SemanticPromotionShadowStage> coveredStages(
            SemanticPromotionShadowIntegratedReport report
    ) {
        if (report == null) {
            return Set.of();
        }

        EnumSet<SemanticPromotionShadowStage> result =
                EnumSet.noneOf(
                        SemanticPromotionShadowStage.class
                );

        report.stages().stream()
                .filter(stage ->
                        stage.eligibleObservationCount() > 0
                )
                .map(stage ->
                        stage.stage()
                )
                .forEach(result::add);

        return result;
    }

    private String resolveScopeKey(
            String repositoryKey
    ) {
        return properties.getScope()
                == SemanticPromotionGateScope.REPOSITORY
                ? repositoryKey
                : GLOBAL_SCOPE_KEY;
    }

    private String normalizeRunId(
            String runId
    ) {
        if (runId == null || runId.isBlank()) {
            return "<unknown-run>";
        }

        return runId.trim();
    }

    private String normalizeRepositoryKey(
            String repoUrl
    ) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return UNKNOWN_REPOSITORY;
        }

        String normalized =
                repoUrl.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace('\\', '/');

        while (normalized.endsWith("/")) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 1
                    );
        }

        if (normalized.endsWith(".git")) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 4
                    );
        }

        return normalized.isBlank()
                ? UNKNOWN_REPOSITORY
                : normalized;
    }

    private static final class GateState {

        private int consecutiveSuccesses;

        private long cumulativeMatchedCandidates;

        private final LinkedHashSet<String>
                distinctRepositories =
                new LinkedHashSet<>();

        private final EnumSet<SemanticPromotionShadowStage>
                coveredStages =
                EnumSet.noneOf(
                        SemanticPromotionShadowStage.class
                );

        private final LinkedHashSet<String>
                processedRunIds =
                new LinkedHashSet<>();

        private void resetSuccessWindow() {
            consecutiveSuccesses = 0;
            cumulativeMatchedCandidates = 0;
            distinctRepositories.clear();
            coveredStages.clear();
        }

        private void rememberRun(
                String runId,
                int maxTrackedRunIds
        ) {
            processedRunIds.add(runId);

            while (processedRunIds.size()
                    > maxTrackedRunIds) {
                Iterator<String> iterator =
                        processedRunIds.iterator();

                if (!iterator.hasNext()) {
                    break;
                }

                iterator.next();
                iterator.remove();
            }
        }
    }
}
