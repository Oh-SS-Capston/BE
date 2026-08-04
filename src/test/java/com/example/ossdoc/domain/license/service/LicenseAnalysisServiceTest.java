package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.support.LicenseAnalysisJsonAssembler;
import com.example.ossdoc.domain.license.support.LicenseCatalog;
import com.example.ossdoc.domain.license.support.ProjectLicenseCandidateCollector;
import com.example.ossdoc.domain.license.support.ProjectLicenseSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseAnalysisServiceTest {

    private final LicenseCatalog licenseCatalog = new LicenseCatalog();
    private final LicenseAnalysisService licenseAnalysisService = new LicenseAnalysisService(
            new ProjectLicenseCandidateCollector(licenseCatalog),
            new ProjectLicenseSelector(licenseCatalog),
            new LicenseAnalysisJsonAssembler(licenseCatalog)
    );

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("repoRoot를 받아 후보 수집부터 JSON 조립까지 한 번에 실행한다")
    void analyze_buildsLicenseAnalysisJsonFromRepoRoot() throws IOException {
        Files.writeString(tempDir.resolve("LICENSE"), """
                Apache License
                Version 2.0, January 2004
                http://www.apache.org/licenses/
                """);

        LicenseAnalysisJson result = licenseAnalysisService.analyze("run-license", tempDir);

        assertThat(result.getRunId()).isEqualTo("run-license");
        assertThat(result.getGeneratedAt()).isNotNull();
        assertThat(result.getProjectLicense().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(result.getProjectLicense().getEvidenceIds()).containsExactly("license_evidence_001");
        assertThat(result.getEvidences()).hasSize(1);
        assertThat(result.getReviewItems()).isEmpty();
        assertThat(result.getDisplayPolicy().getRequireManualReview()).isFalse();
    }

    @Test
    @DisplayName("서로 다른 후보가 발견되면 대표 후보와 충돌 검토 항목을 함께 반환한다")
    void analyze_keepsConflictReviewItemWhenCandidatesDisagree() throws IOException {
        Files.writeString(tempDir.resolve("LICENSE"), """
                Apache License
                Version 2.0, January 2004
                """);
        Files.writeString(tempDir.resolve("README.md"), """
                # Sample

                This project is licensed under the MIT License.
                """);

        LicenseAnalysisJson result = licenseAnalysisService.analyze("run-conflict", tempDir);

        assertThat(result.getProjectLicense().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(result.getEvidences()).hasSize(2);
        assertThat(result.getReviewItems()).hasSize(1);
        assertThat(result.getReviewItems().get(0).getType()).isEqualTo("PROJECT_LICENSE_CONFLICT");
        assertThat(result.getDisplayPolicy().getRequireManualReview()).isTrue();
    }

    @Test
    @DisplayName("라이선스 후보가 없으면 UNKNOWN 결과를 반환해 호출자가 항상 같은 JSON 구조를 받게 한다")
    void analyze_returnsUnknownJsonWhenLicenseCandidateDoesNotExist() {
        LicenseAnalysisJson result = licenseAnalysisService.analyze("run-empty", tempDir);

        assertThat(result.getProjectLicense().getSpdxId()).isEqualTo("UNKNOWN");
        assertThat(result.getProjectLicense().getEvidenceIds()).isEmpty();
        assertThat(result.getEvidences()).isEmpty();
        assertThat(result.getReviewItems()).hasSize(1);
        assertThat(result.getReviewItems().get(0).getType()).isEqualTo("PROJECT_LICENSE_UNKNOWN");
        assertThat(result.getDisplayPolicy().getShowUnknownLicenseWarning()).isTrue();
        assertThat(result.getDisplayPolicy().getRequireManualReview()).isTrue();
    }
}
