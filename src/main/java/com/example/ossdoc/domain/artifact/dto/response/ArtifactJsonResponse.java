package com.example.ossdoc.domain.artifact.dto.response;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ArtifactJsonResponse {

    private Long artifactId;
    private String runId;
    private String kind;
    private String schemaVersion;
    private String contentType;
    private String path;

    /*
     * content : 실제 class_diagram.json 본문입니다.
     * 프론트는 이 content.nodes, content.edges를 사용해서 렌더링합니다.
     */
    private JsonNode content;

    public static ArtifactJsonResponse from(Artifact artifact) {
        return ArtifactJsonResponse.builder()
                .artifactId(artifact.getArtifactId())
                .runId(artifact.getRun().getRunId())
                .kind(artifact.getKind().name())
                .schemaVersion(artifact.getSchemaVersion())
                .contentType(artifact.getContentType())
                .path(artifact.getPath())
                .content(artifact.getMeta())
                .build();
    }
}