package com.example.ossdoc.domain.graphstore.model.promotion.gate;

import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStage;

import java.util.List;
import java.util.Set;

/**
 * 실제 GraphStore ingest 한 건을 반영한 책임 전환 Gate 판정.
 */
public record SemanticPromotionGateDecision(
        SemanticPromotionGateStatus status,
        String scopeKey,
        String runId,
        String repositoryKey,
        boolean currentRunShadowReady,
        int currentRunMatchedCandidates,
        int consecutiveSuccesses,
        int requiredConsecutiveSuccesses,
        long cumulativeMatchedCandidates,
        long minimumCumulativeMatchedCandidates,
        int distinctRepositories,
        int requiredDistinctRepositories,
        Set<SemanticPromotionShadowStage> coveredStages,
        Set<SemanticPromotionShadowStage> requiredStages,
        boolean candidatePersistenceFlagEnabled,
        boolean candidatePersistencePermitted,
        List<String> reasons
) {

    public SemanticPromotionGateDecision {
        coveredStages = coveredStages == null
                ? Set.of()
                : Set.copyOf(coveredStages);

        requiredStages = requiredStages == null
                ? Set.of()
                : Set.copyOf(requiredStages);

        reasons = reasons == null
                ? List.of()
                : List.copyOf(reasons);
    }

    public boolean ready() {
        return status
                == SemanticPromotionGateStatus.READY
                || status
                == SemanticPromotionGateStatus
                .READY_BUT_PERSISTENCE_DISABLED;
    }

    public boolean blocked() {
        return status
                == SemanticPromotionGateStatus.BLOCKED;
    }

    public String summary() {
        return "status="
                + status
                + ", scopeKey="
                + scopeKey
                + ", runId="
                + runId
                + ", repository="
                + repositoryKey
                + ", currentRunShadowReady="
                + currentRunShadowReady
                + ", currentRunMatchedCandidates="
                + currentRunMatchedCandidates
                + ", consecutiveSuccesses="
                + consecutiveSuccesses
                + "/"
                + requiredConsecutiveSuccesses
                + ", cumulativeMatchedCandidates="
                + cumulativeMatchedCandidates
                + "/"
                + minimumCumulativeMatchedCandidates
                + ", distinctRepositories="
                + distinctRepositories
                + "/"
                + requiredDistinctRepositories
                + ", coveredStages="
                + coveredStages
                + ", requiredStages="
                + requiredStages
                + ", candidatePersistenceFlagEnabled="
                + candidatePersistenceFlagEnabled
                + ", candidatePersistencePermitted="
                + candidatePersistencePermitted
                + ", reasons="
                + reasons;
    }
}
