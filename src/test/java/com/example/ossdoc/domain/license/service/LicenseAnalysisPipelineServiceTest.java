package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.ProjectLicenseJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseAnalysisPipelineServiceTest {

    @Mock
    private RepoRunRepository repoRunRepository;

    @Mock
    private RepoRootResolver repoRootResolver;

    @Mock
    private LicenseAnalysisService licenseAnalysisService;

    @Mock
    private LicenseAnalysisArtifactPublisher artifactPublisher;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SNAPSHOT 결과의 workspaceRoot에서 실제 repoRoot를 찾아 분석하고 Artifact를 발행한다")
    void analyzeAndPublish_resolvesRepoRootThenPublishesArtifact() {
        LicenseAnalysisPipelineService pipelineService = new LicenseAnalysisPipelineService(
                repoRunRepository,
                repoRootResolver,
                licenseAnalysisService,
                artifactPublisher
        );

        RepoRun run = run("run-license", tempDir.toString());
        Path workspaceRepoRoot = tempDir.resolve("repo").toAbsolutePath().normalize();
        Path actualRepoRoot = workspaceRepoRoot.resolve("project").toAbsolutePath().normalize();
        LicenseAnalysisJson output = LicenseAnalysisJson.builder()
                .runId("run-license")
                .projectLicense(ProjectLicenseJson.builder()
                        .spdxId("Apache-2.0")
                        .build())
                .build();
        Artifact savedArtifact = artifact(run, 20L);

        when(repoRunRepository.findById("run-license")).thenReturn(Optional.of(run));
        when(repoRootResolver.resolveActualRoot(workspaceRepoRoot)).thenReturn(actualRepoRoot);
        when(licenseAnalysisService.analyze("run-license", actualRepoRoot)).thenReturn(output);
        when(artifactPublisher.publish(run, output)).thenReturn(savedArtifact);

        Artifact result = pipelineService.analyzeAndPublish("run-license");

        assertThat(result).isSameAs(savedArtifact);
        verify(repoRootResolver).resolveActualRoot(workspaceRepoRoot);
        verify(licenseAnalysisService).analyze("run-license", actualRepoRoot);
        verify(artifactPublisher).publish(run, output);
    }

    private RepoRun run(String runId, String workspaceRoot) {
        return new RepoRun(
                runId,
                null,
                "https://github.com/apache/commons-lang",
                "apache",
                "commons-lang",
                "master",
                "abcdef1",
                workspaceRoot,
                null
        );
    }

    private Artifact artifact(RepoRun run, Long artifactId) {
        ObjectMapper objectMapper = new ObjectMapper();
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
