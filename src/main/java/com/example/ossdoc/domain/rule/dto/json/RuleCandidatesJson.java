package com.example.ossdoc.domain.rule.dto.json;

import lombok.Builder;

import java.util.List;

@Builder
public record RuleCandidatesJson(
        String schemaVersion,
        String runId,
        String generatedAt,
        RuleCandidateSummaryJson summary,
        List<RuleCandidateItem> candidates
) {
}