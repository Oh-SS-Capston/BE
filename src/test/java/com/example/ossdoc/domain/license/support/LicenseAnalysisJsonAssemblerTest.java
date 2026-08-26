package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseReviewItemJson;
import com.example.ossdoc.domain.license.enums.LicenseAnalysisScope;
import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseEvidenceType;
import com.example.ossdoc.domain.license.model.ProjectLicenseAnalysisResult;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseAnalysisJsonAssemblerTest {

    private static final OffsetDateTime GENERATED_AT = OffsetDateTime.parse("2026-07-30T10:15:30+09:00");

    private final LicenseCatalog licenseCatalog = new LicenseCatalog();
    private final ProjectLicenseSelector selector = new ProjectLicenseSelector(licenseCatalog);
    private final LicenseAnalysisJsonAssembler assembler = new LicenseAnalysisJsonAssembler(licenseCatalog);

    @Test
    @DisplayName("선택된 대표 라이선스와 근거를 license_analysis.json 구조로 조립한다")
    void assemble_buildsProjectLicenseSummaryAndEvidenceList() {
        ProjectLicenseCandidate apacheCandidate = candidate(
                "Apache-2.0",
                LicenseCandidateSource.LICENSE_FILE,
                LicenseEvidenceType.LICENSE_FILE,
                "LICENSE",
                0.98
        );
        ProjectLicenseAnalysisResult result = selector.select(List.of(apacheCandidate));

        LicenseAnalysisJson json = assembler.assemble("run-1", GENERATED_AT, result);

        assertThat(json.getSchemaVersion()).isEqualTo("1.0");
        assertThat(json.getRunId()).isEqualTo("run-1");
        assertThat(json.getGeneratedAt()).isEqualTo(GENERATED_AT);
        assertThat(json.getAnalysisScope()).isEqualTo(LicenseAnalysisScope.PROJECT_LICENSE_ONLY);
        assertThat(json.getProjectLicense().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(json.getProjectLicense().getEvidenceIds()).containsExactly("license_evidence_001");
        assertThat(json.getReviewItems()).isEmpty();
        assertThat(json.getEvidences()).hasSize(1);
        assertThat(json.getEvidences().get(0).getEvidenceType()).isEqualTo("LICENSE_FILE");
        assertThat(json.getEvidences().get(0).getAttrs())
                .containsEntry("source", "license_file")
                .containsEntry("matchedSpdxId", "Apache-2.0");
        assertThat(json.getDisplayPolicy().getShowReviewItems()).isFalse();
        assertThat(json.getDisplayPolicy().getRequireManualReview()).isFalse();
        assertThat(json.getDisplayPolicy().getWarnings())
                .contains("대표 라이선스만 분석한 결과입니다. 의존성 라이선스는 포함하지 않습니다.");
    }

    @Test
    @DisplayName("서로 다른 대표 라이선스 후보가 있으면 충돌 검토 항목과 근거 ID를 함께 조립한다")
    void assemble_addsConflictReviewItemWhenCandidatesDisagree() {
        ProjectLicenseCandidate apacheCandidate = candidate(
                "Apache-2.0",
                LicenseCandidateSource.LICENSE_FILE,
                LicenseEvidenceType.LICENSE_FILE,
                "LICENSE",
                0.98
        );
        ProjectLicenseCandidate mitCandidate = candidate(
                "MIT",
                LicenseCandidateSource.README_FILE,
                LicenseEvidenceType.README,
                "README.md",
                0.65
        );
        ProjectLicenseAnalysisResult result = selector.select(List.of(apacheCandidate, mitCandidate));

        LicenseAnalysisJson json = assembler.assemble("run-conflict", GENERATED_AT, result);

        assertThat(json.getProjectLicense().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(json.getProjectLicense().getEvidenceIds()).containsExactly("license_evidence_001");
        assertThat(json.getDisplayPolicy().getShowReviewItems()).isTrue();
        assertThat(json.getDisplayPolicy().getRequireManualReview()).isTrue();
        assertThat(json.getReviewItems()).hasSize(1);

        LicenseReviewItemJson reviewItem = json.getReviewItems().get(0);
        assertThat(reviewItem.getType()).isEqualTo("PROJECT_LICENSE_CONFLICT");
        assertThat(reviewItem.getMessage()).contains("Apache-2.0", "MIT");
        assertThat(reviewItem.getEvidenceIds()).containsExactly("license_evidence_001", "license_evidence_002");
    }

    @Test
    @DisplayName("후보가 없으면 UNKNOWN 대표 라이선스와 수동 확인 경고를 조립한다")
    void assemble_buildsUnknownResultWhenCandidateDoesNotExist() {
        ProjectLicenseAnalysisResult result = selector.select(List.of());

        LicenseAnalysisJson json = assembler.assemble("run-unknown", GENERATED_AT, result);

        assertThat(json.getProjectLicense().getSpdxId()).isEqualTo("UNKNOWN");
        assertThat(json.getProjectLicense().getEvidenceIds()).isEmpty();
        assertThat(json.getEvidences()).isEmpty();
        assertThat(json.getReviewItems()).hasSize(1);
        assertThat(json.getReviewItems().get(0).getType()).isEqualTo("PROJECT_LICENSE_UNKNOWN");
        assertThat(json.getDisplayPolicy().getShowUnknownLicenseWarning()).isTrue();
        assertThat(json.getDisplayPolicy().getRequireManualReview()).isTrue();
    }

    /**
     * JSON 조립 테스트에서 사용할 후보를 만듭니다.
     * 여기서는 파일 수집 로직이 아니라 출력물 변환을 검증하므로, assembler가 읽는 필드만 명확히 채웁니다.
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
