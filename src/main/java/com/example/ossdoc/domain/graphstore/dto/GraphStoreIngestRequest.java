package com.example.ossdoc.domain.graphstore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class GraphStoreIngestRequest {
    @NotBlank
    private String runId;
    private Long artifactId;
}