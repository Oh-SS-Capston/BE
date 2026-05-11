package com.example.ossdoc.domain.graphstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphStoreIngestRequest {

    @NotBlank
    private String runId;

    /*
     * null이면 GraphStoreIngestService가 해당 run의 최신 FACTS_JSON artifact를 찾습니다.
     */
    private Long artifactId;
}