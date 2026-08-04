package com.example.ossdoc.domain.license.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 라이선스 사용 전에 사람이 얼마나 주의해서 검토해야 하는지를 나타냅니다.
 * 위험/안전의 단정이 아니라 추가 확인 필요도를 표현하는 지표입니다.
 */
@Getter
@RequiredArgsConstructor
public enum LicenseReviewLevel {

    /**
     * 일반적인 고지 의무 중심으로 검토 부담이 비교적 낮은 항목입니다.
     * 예: MIT, Apache-2.0, BSD 계열
     */
    LOW("low", "낮음"),

    /**
     * 파일 단위 공개 의무나 고지 범위를 확인해야 하는 항목입니다.
     * 예: LGPL, MPL, EPL 계열
     */
    MEDIUM("medium", "보통"),

    /**
     * 배포/결합 방식에 따라 강한 의무가 발생할 수 있어 세부 검토가 필요한 항목입니다.
     * 예: GPL, AGPL 계열
     */
    HIGH("high", "높음"),

    /**
     * 라이선스를 식별하지 못했거나 근거가 충돌해 사람이 직접 확인해야 하는 항목입니다.
     */
    NEEDS_REVIEW("needs_review", "확인 필요");

    /**
     * JSON이나 정책 매핑에서 사용할 안정적인 코드값입니다.
     */
    private final String code;

    /**
     * 화면에서 사용자에게 보여줄 한국어 검토 수준 이름입니다.
     */
    private final String label;
}
