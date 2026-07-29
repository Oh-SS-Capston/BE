package com.example.ossdoc.domain.license.artifact.output;

import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 프로젝트 대표 라이선스 판단 결과입니다.
 * 저장소 자체를 도입할 때 가장 먼저 확인해야 하는 라이선스 정보를 담습니다.
 */
@Getter
@Builder
@Jacksonized
public class ProjectLicenseJson {

    /**
     * SPDX 기준으로 정규화한 대표 라이선스 식별자입니다.
     * 예: Apache-2.0, MIT, GPL-3.0
     */
    private String spdxId;

    /**
     * 사용자에게 보여줄 라이선스 이름입니다.
     * SPDX ID만으로 이해하기 어려울 때 보조 표시명으로 사용합니다.
     */
    private String displayName;

    /**
     * 라이선스의 큰 성격입니다.
     * 화면에서 허용형/카피레프트/확인 필요 같은 분류 배지로 사용할 수 있습니다.
     */
    private LicenseFamily family;

    /**
     * 사용 전에 어느 정도의 추가 검토가 필요한지 나타냅니다.
     * 위험 판정이 아니라 검토 필요도입니다.
     */
    private LicenseReviewLevel reviewLevel;

    /**
     * 대표 라이선스 판단의 근거 강도입니다.
     * 1.0에 가까울수록 LICENSE 전문 매칭처럼 강한 근거이고, 낮을수록 README 언급 등 약한 근거입니다.
     */
    private Double confidence;

    /**
     * 사용자가 바로 이해할 수 있는 짧은 설명입니다.
     * 이 값은 LLM 생성문이 아니라 사전에 정의한 라이선스 카탈로그 문구에서 가져오는 것을 전제로 합니다.
     */
    private String summary;

    /**
     * 해당 라이선스에서 일반적으로 허용되는 행위 목록입니다.
     * 예: 상업적 사용, 수정, 배포
     */
    private List<String> permissions;

    /**
     * 해당 라이선스를 사용할 때 지켜야 할 주요 의무 목록입니다.
     * 예: 저작권 고지 유지, 라이선스 전문 포함
     */
    private List<String> obligations;

    /**
     * 배포나 서비스 제공 시 특히 주의해야 할 사항입니다.
     * 예: 소스 공개 검토 필요, 네트워크 사용 조항 확인 필요
     */
    private List<String> notices;

    /**
     * 이 대표 라이선스 판단을 뒷받침하는 근거 ID 목록입니다.
     * LicenseEvidenceJson.evidenceId와 연결됩니다.
     */
    private List<String> evidenceIds;
}
