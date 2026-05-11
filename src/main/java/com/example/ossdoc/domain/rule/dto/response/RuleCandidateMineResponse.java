package com.example.ossdoc.domain.rule.dto.response;

import lombok.Builder;

@Builder
public record RuleCandidateMineResponse(
        String runId,
        Long ruleCandidatesArtifactId,
        int totalCandidates,
        int highConfidenceCount,
        int mediumConfidenceCount,
        int lowConfidenceCount,
        boolean forceRebuildApplied
) {
}
