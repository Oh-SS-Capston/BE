package com.example.ossdoc.domain.rule.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RuleCandidateMineRequest(
        @NotBlank(message = "runId is required.")
        String runId,
        Long factsArtifactId,
        Boolean forceRebuild
) {
    /**
     * forceRebuild 값이 요청 본문에 명시되었는지 여부를 반환한다.
     */
    public boolean hasForceRebuildFlag() {
        return forceRebuild != null;
    }

    public boolean isForceRebuild() {
        return Boolean.TRUE.equals(forceRebuild);
    }
}
