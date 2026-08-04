package com.example.ossdoc.domain.license.artifact.output;

import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 사용자가 반드시 확인해야 하는 라이선스 검토 항목입니다.
 * 화면의 "검토 필요 항목" 카드 목록으로 표시됩니다.
 */
@Getter
@Builder
@Jacksonized
public class LicenseReviewItemJson {

    /**
     * 검토 항목의 유형입니다.
     * 예: PROJECT_LICENSE_UNKNOWN, PROJECT_LICENSE_CONFLICT, PROJECT_LICENSE_HIGH_REVIEW
     */
    private String type;

    /**
     * 이 항목의 검토 수준입니다.
     * 사용자가 어떤 항목을 먼저 봐야 하는지 정렬하는 기준으로 사용할 수 있습니다.
     */
    private LicenseReviewLevel reviewLevel;

    /**
     * 화면 카드의 제목입니다.
     * 예: 대표 라이선스를 식별하지 못했습니다, LICENSE와 README의 라이선스 정보가 다릅니다.
     */
    private String title;

    /**
     * 검토 항목에 대한 상세 설명입니다.
     * 왜 확인이 필요한지 사용자에게 알려주는 문장입니다.
     */
    private String message;

    /**
     * 검토 대상의 식별자입니다.
     * 대표 라이선스 MVP에서는 보통 project를 사용합니다.
     * 나중에 파일 단위 검토가 추가되면 LICENSE, README 같은 대상명을 넣을 수 있습니다.
     */
    private String targetId;

    /**
     * 사용자가 다음에 무엇을 확인하면 좋은지 알려주는 권장 조치입니다.
     * 예: 저장소 루트의 LICENSE 파일과 README의 라이선스 문구를 함께 확인하세요.
     */
    private String recommendation;

    /**
     * 이 검토 항목을 뒷받침하는 근거 ID 목록입니다.
     */
    private List<String> evidenceIds;
}
