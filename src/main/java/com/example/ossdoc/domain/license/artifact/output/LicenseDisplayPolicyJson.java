package com.example.ossdoc.domain.license.artifact.output;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 라이선스 분석 화면의 표시 정책입니다.
 * 대표 라이선스 MVP 화면에서 어떤 영역과 경고를 보여줄지 결정합니다.
 *
 * <p>이 객체가 필요한 이유:
 * 분석 결과가 UNKNOWN이거나 근거가 부족할 때도 화면은 깨지지 않아야 합니다.
 * 서비스 로직은 이 정책을 채워서 프론트가 동일한 JSON 구조 안에서 표시 여부만 판단하게 합니다.
 */
@Getter
@Builder
@Jacksonized
public class LicenseDisplayPolicyJson {

    /**
     * 라이선스 분석 페이지 자체를 표시할 수 있는지 여부입니다.
     * 대표 라이선스 판단 결과가 UNKNOWN이어도 근거와 경고를 보여줄 수 있다면 true로 둘 수 있습니다.
     */
    private Boolean displayable;

    /**
     * 대표 라이선스 요약 영역을 보여줄지 여부입니다.
     */
    private Boolean showProjectLicenseSummary;

    /**
     * 검토 필요 항목 영역을 보여줄지 여부입니다.
     * LICENSE/README 불일치, 라이선스 미식별, 근거 부족 같은 항목이 있을 때 true가 됩니다.
     */
    private Boolean showReviewItems;

    /**
     * 라이선스 판단 근거 목록을 보여줄지 여부입니다.
     * 사용자가 어떤 파일과 문장 때문에 해당 라이선스로 판단했는지 확인할 수 있게 합니다.
     */
    private Boolean showEvidenceList;

    /**
     * 대표 라이선스를 식별하지 못했을 때 경고를 보여줄지 여부입니다.
     * 프로젝트 자체의 대표 라이선스를 UNKNOWN으로 판단했을 때 사용합니다.
     */
    private Boolean showUnknownLicenseWarning;

    /**
     * GPL/AGPL 등 강한 검토가 필요한 대표 라이선스가 감지되었을 때 경고를 보여줄지 여부입니다.
     */
    private Boolean showCopyleftWarning;

    /**
     * 사람이 직접 확인해야 한다는 최상단 경고를 보여줄지 여부입니다.
     * 대표 라이선스가 UNKNOWN이거나 근거 충돌이 있을 때 true가 됩니다.
     */
    private Boolean requireManualReview;

    /**
     * 화면에서 사용자에게 함께 보여줄 경고 메시지 목록입니다.
     */
    private List<String> warnings;
}
