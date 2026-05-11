package com.example.ossdoc.domain.rule.dto.json;

import lombok.Builder;

import java.util.List;

@Builder
public record RuleCandidateDisplayPolicyJson(
        String sizeTier,
        int totalCandidates,
        int targetMin,
        int targetMax,
        int recommendedPrimaryCount,
        List<Long> primaryCandidateIds,
        List<Long> exploratoryCandidateIds
) {
}
