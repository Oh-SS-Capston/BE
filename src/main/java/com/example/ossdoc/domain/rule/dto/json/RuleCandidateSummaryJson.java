package com.example.ossdoc.domain.rule.dto.json;

import lombok.Builder;

@Builder
public record RuleCandidateSummaryJson(
        int totalCandidates,
        int highConfidenceCount,
        int mediumConfidenceCount,
        int lowConfidenceCount
) {
}