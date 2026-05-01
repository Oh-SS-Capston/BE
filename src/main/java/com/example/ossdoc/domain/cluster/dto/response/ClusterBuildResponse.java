package com.example.ossdoc.domain.cluster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ClusterBuildResponse {
    private String runId;
    private int subsystemCount;
    private int rankedSymbolCount;
    private Long rankingsArtifactId;
    private Long subsystemsArtifactId;
}
