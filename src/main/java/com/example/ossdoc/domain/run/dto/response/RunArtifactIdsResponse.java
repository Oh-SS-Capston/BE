package com.example.ossdoc.domain.run.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RunArtifactIdsResponse {

    private Long jobManifestArtifactId;
    private Long buildManifestArtifactId;
    private Long factsArtifactId;
    private Long graphStatsArtifactId;

    private Long rankingsArtifactId;
    private Long subsystemsArtifactId;
    private Long classDiagramArtifactId;

    /*
     * Rule 단계 산출물
     */
    private Long ruleCandidatesArtifactId;

    /*
     * LLM 단계 산출물
     */
    private Long llmRefinedRulesArtifactId;
    private Long llmScenarioSpecsArtifactId;
    private Long llmSubsystemSummariesArtifactId;
    private Long llmApiDocsArtifactId;
    private Long llmFileTreeDocsArtifactId;
}