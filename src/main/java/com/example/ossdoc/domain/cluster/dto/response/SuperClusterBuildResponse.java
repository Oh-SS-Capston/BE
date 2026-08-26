package com.example.ossdoc.domain.cluster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SuperClusterBuildResponse {
    private String runId;
    private int level1Count;
    private int superClusterCount;
    private Long artifactId;
}
