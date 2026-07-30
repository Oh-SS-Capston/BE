package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseEvidenceType;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectLicenseCandidateCollectorTest {

    private final ProjectLicenseCandidateCollector collector =
            new ProjectLicenseCandidateCollector(new LicenseCatalog());

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("LICENSE 파일에서 Apache-2.0 대표 라이선스 후보와 근거 위치를 수집한다")
    void collect_detectsApacheFromLicenseFile() throws IOException {
        Files.writeString(tempDir.resolve("LICENSE"), """
                Apache License
                Version 2.0, January 2004
                http://www.apache.org/licenses/
                """);

        List<ProjectLicenseCandidate> candidates = collector.collect(tempDir);

        assertThat(candidates).hasSize(1);
        ProjectLicenseCandidate candidate = candidates.get(0);
        assertThat(candidate.getProfile().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(candidate.getSource()).isEqualTo(LicenseCandidateSource.LICENSE_FILE);
        assertThat(candidate.getEvidenceType()).isEqualTo(LicenseEvidenceType.LICENSE_FILE);
        assertThat(candidate.getPath()).isEqualTo("LICENSE");
        assertThat(candidate.getStartLine()).isEqualTo(1);
        assertThat(candidate.getEndLine()).isGreaterThanOrEqualTo(2);
        assertThat(candidate.getConfidence()).isEqualTo(0.98);
        assertThat(candidate.getSnippet()).contains("Apache License");
    }

    @Test
    @DisplayName("README와 pom.xml의 라이선스 문구를 보조 후보로 함께 수집한다")
    void collect_detectsReadmeAndPomCandidates() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), """
                # Sample

                This project is licensed under the MIT License.
                """);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <licenses>
                        <license>
                            <name>Apache License, Version 2.0</name>
                        </license>
                    </licenses>
                </project>
                """);

        List<ProjectLicenseCandidate> candidates = collector.collect(tempDir);

        assertThat(candidates).hasSize(2);
        assertThat(candidates)
                .extracting(candidate -> candidate.getProfile().getSpdxId())
                .containsExactlyInAnyOrder("MIT", "Apache-2.0");
        assertThat(candidates)
                .extracting(ProjectLicenseCandidate::getSource)
                .containsExactlyInAnyOrder(LicenseCandidateSource.README_FILE, LicenseCandidateSource.MAVEN_POM);
    }

    @Test
    @DisplayName("COPYING 파일의 GPL 전문처럼 라이선스명이 여러 줄에 나뉘어 있어도 후보를 수집한다")
    void collect_detectsGplFromMultiLineCopyingFile() throws IOException {
        Files.writeString(tempDir.resolve("COPYING"), """
                GNU GENERAL PUBLIC LICENSE
                Version 3, 29 June 2007

                Everyone is permitted to copy and distribute verbatim copies.
                """);

        List<ProjectLicenseCandidate> candidates = collector.collect(tempDir);

        assertThat(candidates).hasSize(1);
        ProjectLicenseCandidate candidate = candidates.get(0);
        assertThat(candidate.getProfile().getSpdxId()).isEqualTo("GPL-3.0");
        assertThat(candidate.getSource()).isEqualTo(LicenseCandidateSource.COPYING_FILE);
        assertThat(candidate.getConfidence()).isEqualTo(0.95);
        assertThat(candidate.getSnippet()).contains("GNU GENERAL PUBLIC LICENSE");
    }

    @Test
    @DisplayName("LICENSE 파일은 존재하지만 지원 라이선스로 식별하지 못하면 UNKNOWN 후보로 남긴다")
    void collect_keepsUnknownCandidateWhenLicenseFileExists() throws IOException {
        Files.writeString(tempDir.resolve("LICENSE"), """
                Internal Company Research License
                Do not redistribute without approval.
                """);

        List<ProjectLicenseCandidate> candidates = collector.collect(tempDir);

        assertThat(candidates).hasSize(1);
        ProjectLicenseCandidate candidate = candidates.get(0);
        assertThat(candidate.getProfile().getSpdxId()).isEqualTo("UNKNOWN");
        assertThat(candidate.getEvidenceType()).isEqualTo(LicenseEvidenceType.UNKNOWN_LICENSE_FILE);
        assertThat(candidate.getConfidence()).isEqualTo(0.20);
        assertThat(candidate.getNote()).contains("식별하지 못했습니다");
    }

    @Test
    @DisplayName("라이선스 관련 루트 파일이 없으면 빈 후보 목록을 반환한다")
    void collect_returnsEmptyWhenNoLicenseFiles() {
        List<ProjectLicenseCandidate> candidates = collector.collect(tempDir);

        assertThat(candidates).isEmpty();
    }
}
