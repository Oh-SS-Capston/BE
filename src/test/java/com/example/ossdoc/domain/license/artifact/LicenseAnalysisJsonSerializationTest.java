package com.example.ossdoc.domain.license.artifact;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseDisplayPolicyJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseEvidenceJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseReviewItemJson;
import com.example.ossdoc.domain.license.artifact.output.ProjectLicenseJson;
import com.example.ossdoc.domain.license.enums.LicenseAnalysisScope;
import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseAnalysisJsonSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("대표 라이선스 전용 분석 JSON은 화면이 사용할 핵심 필드를 유지하며 직렬화 round-trip에 성공한다")
    void licenseAnalysisJson_roundTrip() throws Exception {
        LicenseAnalysisJson original = LicenseAnalysisJson.builder()
                .schemaVersion("1.0")
                .runId("run_license_001")
                .analysisScope(LicenseAnalysisScope.PROJECT_LICENSE_ONLY)
                .generatedAt(OffsetDateTime.parse("2026-06-30T10:15:30+09:00"))
                .projectLicense(ProjectLicenseJson.builder()
                        .spdxId("Apache-2.0")
                        .displayName("Apache License 2.0")
                        .family(LicenseFamily.PERMISSIVE)
                        .reviewLevel(LicenseReviewLevel.LOW)
                        .confidence(0.98)
                        .summary("상업적 사용, 수정, 배포가 비교적 자유로운 라이선스입니다.")
                        .permissions(List.of("상업적 사용", "수정", "배포"))
                        .obligations(List.of("저작권 고지 유지", "라이선스 전문 포함"))
                        .notices(List.of("특허권 관련 조항을 함께 확인해야 합니다."))
                        .evidenceIds(List.of("ev_project_license"))
                        .build())
                .displayPolicy(LicenseDisplayPolicyJson.builder()
                        .displayable(true)
                        .showProjectLicenseSummary(true)
                        .showReviewItems(true)
                        .showEvidenceList(true)
                        .showUnknownLicenseWarning(false)
                        .showCopyleftWarning(false)
                        .requireManualReview(false)
                        .warnings(List.of("대표 라이선스만 분석한 결과입니다. 의존성 라이선스는 포함하지 않습니다."))
                        .build())
                .reviewItems(List.of(
                        LicenseReviewItemJson.builder()
                                .type("PROJECT_LICENSE_NOTICE")
                                .reviewLevel(LicenseReviewLevel.LOW)
                                .title("Apache-2.0 대표 라이선스 감지")
                                .message("LICENSE 파일에서 Apache-2.0과 일치하는 근거를 찾았습니다.")
                                .targetId("project")
                                .recommendation("배포 시 저작권 고지와 라이선스 전문을 유지하세요.")
                                .evidenceIds(List.of("ev_project_license"))
                                .build()
                ))
                .evidences(List.of(
                        LicenseEvidenceJson.builder()
                                .evidenceId("ev_project_license")
                                .evidenceType("LICENSE_FILE")
                                .path("LICENSE")
                                .startLine(1)
                                .endLine(20)
                                .snippet("Apache License Version 2.0")
                                .confidence(0.98)
                                .attrs(Map.of("matchedSpdxId", "Apache-2.0"))
                                .build()
                ))
                .build();

        String json = objectMapper.writeValueAsString(original);
        LicenseAnalysisJson restored = objectMapper.readValue(json, LicenseAnalysisJson.class);

        assertThat(restored.getSchemaVersion()).isEqualTo("1.0");
        assertThat(restored.getRunId()).isEqualTo("run_license_001");
        assertThat(restored.getAnalysisScope()).isEqualTo(LicenseAnalysisScope.PROJECT_LICENSE_ONLY);
        assertThat(restored.getProjectLicense().getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(restored.getProjectLicense().getFamily()).isEqualTo(LicenseFamily.PERMISSIVE);
        assertThat(restored.getProjectLicense().getReviewLevel()).isEqualTo(LicenseReviewLevel.LOW);
        assertThat(restored.getDisplayPolicy().getShowProjectLicenseSummary()).isTrue();
        assertThat(restored.getDisplayPolicy().getShowEvidenceList()).isTrue();
        assertThat(restored.getDisplayPolicy().getRequireManualReview()).isFalse();
        assertThat(restored.getReviewItems()).hasSize(1);
        assertThat(restored.getReviewItems().get(0).getTargetId()).isEqualTo("project");
        assertThat(restored.getEvidences()).extracting(LicenseEvidenceJson::getEvidenceId)
                .containsExactly("ev_project_license");
    }
}
