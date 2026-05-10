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
}