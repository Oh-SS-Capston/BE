package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseEvidenceType;
import com.example.ossdoc.domain.license.model.ProjectLicenseAnalysisResult;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectLicenseSelectorTest {

    private final LicenseCatalog licenseCatalog = new LicenseCatalog();
    private final ProjectLicenseSelector selector = new ProjectLicenseSelector(licenseCatalog);

    @Test
    @DisplayName("LICENSE 후보를 README 후보보다 우선 선택하고 서로 다른 SPDX 후보는 충돌로 남긴다")
    void select_prefersLicenseFileAndKeepsConflictCandidates() {
        ProjectLicenseCandidate licenseCandidate = candidate(
                "Apache-2.0",
                LicenseCandidateSource.LICENSE_FILE,
                LicenseEvidenceType.LICENSE_FILE,
                "LICENSE",
                0.98
        );
        ProjectLicenseCandidate readmeCandidate = candidate(
                "MIT",
                LicenseCandidateSource.README_FILE,
                LicenseEvidenceType.README,
                "README.md",
                0.65
        );

        ProjectLicenseAnalysisResult result = selector.select(List.of(readmeCandidate, licenseCandidate));

        assertThat(result.getSelectedProfile().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(result.getSelectedCandidate()).isEqualTo(licenseCandidate);
        assertThat(result.getConflictingCandidates()).containsExactly(readmeCandidate);
        assertThat(result.isKnownCandidateFound()).isTrue();
        assertThat(result.isConflictDetected()).isTrue();
        assertThat(result.isManualReviewRequired()).isTrue();
        assertThat(result.getDecisionReason()).contains("Apache-2.0", "확인이 필요합니다");
    }

    @Test
    @DisplayName("식별 가능한 후보가 없으면 UNKNOWN 대표 라이선스로 선택하고 수동 검토를 요구한다")
    void select_returnsUnknownWhenKnownCandidateDoesNotExist() {
        ProjectLicenseAnalysisResult result = selector.select(List.of());

        assertThat(result.getSelectedProfile().getSpdxId()).isEqualTo("UNKNOWN");
        assertThat(result.getSelectedCandidate()).isNull();
        assertThat(result.getConflictingCandidates()).isEmpty();
        assertThat(result.isKnownCandidateFound()).isFalse();
        assertThat(result.isConflictDetected()).isFalse();
        assertThat(result.isManualReviewRequired()).isTrue();
        assertThat(result.getDecisionReason()).contains("찾지 못했습니다");
    }

    @Test
    @DisplayName("GPL 계열 후보는 충돌이 없어도 높은 검토 수준으로 수동 확인을 요구한다")
    void select_requiresManualReviewForHighReviewLicense() {
        ProjectLicenseCandidate copyingCandidate = candidate(
                "GPL-3.0",
                LicenseCandidateSource.COPYING_FILE,
                LicenseEvidenceType.COPYING_FILE,
                "COPYING",
                0.95
        );

        ProjectLicenseAnalysisResult result = selector.select(List.of(copyingCandidate));

        assertThat(result.getSelectedProfile().getSpdxId()).isEqualTo("GPL-3.0");
        assertThat(result.getSelectedCandidate()).isEqualTo(copyingCandidate);
        assertThat(result.isConflictDetected()).isFalse();
        assertThat(result.isManualReviewRequired()).isTrue();
    }

    /**
     * 선택기 테스트에서 사용할 대표 라이선스 후보를 간단히 만듭니다.
     * 실제 수집기는 파일에서 이 값을 만들고, 여기서는 선택 기준만 검증하기 위해 필요한 필드만 채웁니다.
     */
    private ProjectLicenseCandidate candidate(
            String spdxId,
            LicenseCandidateSource source,
            LicenseEvidenceType evidenceType,
            String path,
            double confidence
    ) {
        return ProjectLicenseCandidate.builder()
                .profile(licenseCatalog.resolveSpdxIdOrUnknown(spdxId))
                .source(source)
                .evidenceType(evidenceType)
                .path(path)
                .startLine(1)
                .endLine(3)
                .snippet(spdxId + " license text")
                .rawLicenseText(spdxId)
                .confidence(confidence)
                .note(path + "에서 " + spdxId + " 후보를 만들었습니다.")
                .build();
    }
}
