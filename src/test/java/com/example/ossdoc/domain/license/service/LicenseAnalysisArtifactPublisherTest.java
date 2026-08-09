package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.ProjectLicenseJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseAnalysisArtifactPublisherTest {

    @Mock
    private ArtifactService artifactService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("대표 라이선스 분석 결과를 정해진 ArtifactKind와 경로로 저장한다")
    void publish_savesLicenseAnalysisJsonArtifact() {
        LicenseAnalysisArtifactPublisher publisher =
                new LicenseAnalysisArtifactPublisher(artifactService, objectMapper);

        RepoRun run = run("run-license");
        LicenseAnalysisJson output = LicenseAnalysisJson.builder()
                .runId("run-license")
                .projectLicense(ProjectLicenseJson.builder()
                        .spdxId("MIT")
                        .build())
                .build();
        Artifact savedArtifact = artifact(run, 10L);

        ArgumentCaptor<JsonNode> contentCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(artifactService.saveJsonArtifact(
                eq(run),
                eq(ArtifactKind.LICENSE_ANALYSIS_JSON),
                eq("1.0"),
                eq("analysis/license_analysis.json"),
                contentCaptor.capture()
        )).thenReturn(savedArtifact);

        Artifact result = publisher.publish(run, output);

        assertThat(result).isSameAs(savedArtifact);
        assertThat(contentCaptor.getValue().path("runId").asText()).isEqualTo("run-license");
        assertThat(contentCaptor.getValue().path("projectLicense").path("spdxId").asText()).isEqualTo("MIT");
        verify(artifactService).saveJsonArtifact(
                eq(run),
                eq(ArtifactKind.LICENSE_ANALYSIS_JSON),
                eq("1.0"),
                eq("analysis/license_analysis.json"),
                eq(contentCaptor.getValue())
        );
    }

    private RepoRun run(String runId) {
        return new RepoRun(
                runId,
                null,
                "https://github.com/jquery/jquery",
                "jquery",
                "jquery",
                "main",
                "abcdef1",
                "C:/data/ossdoc/" + runId,
                null
        );
    }

    private Artifact artifact(RepoRun run, Long artifactId) {
        return new Artifact(
                artifactId,
                run,
                ArtifactKind.LICENSE_ANALYSIS_JSON,
                "1.0",
                "application/json",
                "https://s3.example.com/license_analysis.json",
                objectMapper.createObjectNode()
        );
    }
}
