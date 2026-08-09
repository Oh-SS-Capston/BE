package com.example.ossdoc.domain.license.artifact.output;

import com.example.ossdoc.domain.license.enums.LicenseAnalysisScope;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * license_analysis.json의 최상위 구조입니다.
 * 대표 라이선스 MVP에서는 이 객체 하나만 조회해 프로젝트 라이선스 요약, 검토 필요 항목, 근거 목록을 렌더링합니다.
 *
 * <p>중요한 설계 의도:
 * 이 산출물은 "완전한 라이선스 컴플라이언스 리포트"가 아니라 저장소 자체의 대표 라이선스를 확인하는
 * 근거 기반 요약입니다. 의존성 라이선스 분석과 PDF 리포트는 이후 확장 범위로 분리합니다.
 */
@Getter
@Builder
@Jacksonized
public class LicenseAnalysisJson {

    /**
     * 라이선스 분석 JSON 스키마 버전입니다.
     * 이후 필드가 추가되거나 의미가 바뀔 때 프론트와 백엔드가 호환성을 판단하는 기준입니다.
     */
    private String schemaVersion;

    /**
     * 이 분석 결과가 속한 RepoRun 식별자입니다.
     * 다른 분석 실행 결과와 artifact를 구분하는 기준입니다.
     */
    private String runId;

    /**
     * 이번 라이선스 분석이 어느 범위까지 수행되었는지 나타냅니다.
     * MVP에서는 항상 PROJECT_LICENSE_ONLY를 사용해 의존성 라이선스가 포함되지 않았음을 명확히 표시합니다.
     */
    private LicenseAnalysisScope analysisScope;

    /**
     * 라이선스 분석 JSON이 생성된 시각입니다.
     * 사용자가 오래된 분석 결과인지 판단하거나 캐시 결과를 표시할 때 사용합니다.
     */
    private OffsetDateTime generatedAt;

    /**
     * 분석 대상 프로젝트 자체의 대표 라이선스 판단 결과입니다.
     * 예: 이 저장소는 Apache-2.0으로 판단됨, 근거는 LICENSE 파일임
     */
    private ProjectLicenseJson projectLicense;

    /**
     * 화면에서 어떤 경고와 영역을 보여줄지 결정하는 표시 정책입니다.
     * 예: 확인 필요 항목이 있으면 경고 배너를 보여줌
     */
    private LicenseDisplayPolicyJson displayPolicy;

    /**
     * 사용자가 반드시 확인해야 하는 라이선스 이슈 목록입니다.
     * 대표 라이선스 미식별, LICENSE/README 불일치, 근거 부족 같은 프로젝트 단위 항목이 들어갑니다.
     */
    private List<LicenseReviewItemJson> reviewItems;

    /**
     * 라이선스 판단에 사용한 근거 목록입니다.
     * 각 판단 결과는 evidenceIds로 이 목록의 항목을 참조해야 합니다.
     */
    private List<LicenseEvidenceJson> evidences;
}
