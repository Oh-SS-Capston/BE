package com.example.ossdoc.domain.license.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 라이선스 분석 산출물이 어느 범위까지 분석했는지 나타냅니다.
 * MVP에서는 프로젝트 대표 라이선스만 분석하므로, 화면과 사용자에게 범위를 명확히 알리기 위해 둡니다.
 */
@Getter
@RequiredArgsConstructor
public enum LicenseAnalysisScope {

    /**
     * 저장소 자체의 대표 라이선스만 분석한 결과입니다.
     * 의존성 라이선스, PDF 리포트, 법무 검토 결과는 이 범위에 포함되지 않습니다.
     */
    PROJECT_LICENSE_ONLY("project_license_only", "대표 라이선스만 분석");

    /**
     * JSON 응답과 표시 정책에서 사용할 안정적인 코드값입니다.
     */
    private final String code;

    /**
     * 화면에서 사용자에게 보여줄 한국어 분석 범위 이름입니다.
     */
    private final String label;
}
