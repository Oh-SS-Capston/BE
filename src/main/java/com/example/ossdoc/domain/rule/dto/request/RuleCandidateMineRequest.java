package com.example.ossdoc.domain.rule.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RuleCandidateMineRequest(
        @NotBlank(message = "runId is required.")
        String runId,
        Long factsArtifactId,
        Boolean forceRebuild
) {
    public boolean isForceRebuild() {
        return Boolean.TRUE.equals(forceRebuild);
    }
}