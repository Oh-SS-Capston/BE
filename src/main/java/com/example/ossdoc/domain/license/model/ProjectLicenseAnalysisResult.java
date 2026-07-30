package com.example.ossdoc.domain.license.model;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 대표 라이선스 후보 선택 결과입니다.
 *
 * <p>역할:
 * ProjectLicenseCandidateCollector가 수집한 여러 후보 중 어떤 후보를 대표 라이선스로 선택했는지,
 * 그리고 그 과정에서 UNKNOWN/충돌/높은 검토 수준 같은 검토 필요 신호가 있었는지 담습니다.
 *
 * <p>중요:
 * 이 객체는 아직 화면용 JSON이 아닙니다.
 * 다음 단계의 LicenseAnalysisJsonAssembler가 이 결과를 license_analysis.json 구조로 변환합니다.
 */
@Getter
@Builder
@Jacksonized
public class ProjectLicenseAnalysisResult {

    /**
     * 최종 대표 라이선스로 선택한 표준 프로필입니다.
     * 후보가 없거나 모두 식별 실패인 경우 UNKNOWN 프로필이 들어갑니다.
     */
    private LicenseProfile selectedProfile;

    /**
     * 최종 대표 라이선스 판단에 가장 크게 기여한 후보입니다.
     * 후보 파일이 전혀 없으면 null일 수 있습니다.
     */
    private ProjectLicenseCandidate selectedCandidate;

    /**
     * 수집 단계에서 발견한 모든 후보 목록입니다.
     * JSON 근거 목록을 만들 때 이 후보들이 LicenseEvidenceJson으로 변환됩니다.
     */
    private List<ProjectLicenseCandidate> candidates;

    /**
     * 선택된 대표 라이선스와 다른 SPDX ID를 가진 후보 목록입니다.
     * 예: LICENSE는 Apache-2.0인데 README는 MIT라고 적힌 경우 README 후보가 들어갑니다.
     */
    private List<ProjectLicenseCandidate> conflictingCandidates;

    /**
     * 식별 가능한 대표 라이선스 후보가 하나 이상 있었는지 여부입니다.
     * false이면 UNKNOWN 처리와 수동 확인 안내가 필요합니다.
     */
    private boolean knownCandidateFound;

    /**
     * 서로 다른 SPDX ID 후보가 동시에 발견되었는지 여부입니다.
     * true이면 사용자가 LICENSE/README/빌드 파일을 직접 비교해야 합니다.
     */
    private boolean conflictDetected;

    /**
     * 사람이 직접 확인해야 하는 상태인지 여부입니다.
     * UNKNOWN, 충돌, 높은 검토 수준 라이선스가 여기에 해당합니다.
     */
    private boolean manualReviewRequired;

    /**
     * 선택기가 어떤 이유로 이 결론을 냈는지 설명하는 짧은 문장입니다.
     * 로그, 테스트, 화면 경고 메시지 구성에 사용할 수 있습니다.
     */
    private String decisionReason;
}
