package com.example.ossdoc.domain.graphstore.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GraphStoreIngestResponse {
    private String runId;
    private Long artifactId;
    private int evidencesSaved;
    private int symbolsSaved;
    private int edgesSaved;
    private int edgeEvidenceSaved;
    private int skippedRelations;
    private int observationsDetected;
    private int observationsIgnored;
}
