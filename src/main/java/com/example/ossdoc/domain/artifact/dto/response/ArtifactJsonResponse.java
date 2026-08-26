package com.example.ossdoc.domain.artifact.dto.response;

import com.example.ossdoc.domain.artifact.entity.Artifact;
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
     * JsonNode를 그대로 노출하지 않습니다.
     * Map/List/String/Number 등 일반 Java 값으로 변환된 JSON 본문을 담습니다.
     */
    private Object content;

    public static ArtifactJsonResponse from(Artifact artifact, Object content) {
        return ArtifactJsonResponse.builder()
                .artifactId(artifact.getArtifactId())
                .runId(artifact.getRun().getRunId())
                .kind(artifact.getKind().name())
                .schemaVersion(artifact.getSchemaVersion())
                .contentType(artifact.getContentType())
                .path(artifact.getPath())
                .content(content)
                .build();
    }
}