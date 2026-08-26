package com.example.ossdoc.domain.publicapi.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiMapBuildResponse {
    private int entryPointTotal;
    private int extensionPointTotal;
    private int primaryCount;
    private int secondaryCount;
    private int highConfidenceCount;
    private int medConfidenceCount;
    private int lowConfidenceCount;
    private String apiSurfaceArtifactUrl;
    private String apiMapArtifactUrl;
}
