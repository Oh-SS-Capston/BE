package com.example.ossdoc.domain.license.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 라이선스의 성격을 큰 계열로 분류합니다.
 * 화면에서는 사용자가 해당 라이선스가 어느 정도의 의무를 갖는지 빠르게 이해하는 데 사용합니다.
 */
@Getter
@RequiredArgsConstructor
public enum LicenseFamily {

    /**
     * 사용, 수정, 배포가 비교적 자유로운 계열입니다.
     * 예: MIT, Apache-2.0, BSD 계열
     */
    PERMISSIVE("permissive", "허용형"),

    /**
     * 특정 파일이나 모듈 단위의 공개 의무가 붙을 수 있는 약한 카피레프트 계열입니다.
     * 예: LGPL, MPL, EPL 계열
     */
    WEAK_COPYLEFT("weak_copyleft", "약한 카피레프트"),

    /**
     * 결합/배포 방식에 따라 전체 소스 공개 검토가 필요할 수 있는 카피레프트 계열입니다.
     * 예: GPL 계열
     */
    COPYLEFT("copyleft", "카피레프트"),

    /**
     * 네트워크 서비스 제공 형태에서도 공개 의무 검토가 필요할 수 있는 계열입니다.
     * 예: AGPL 계열
     */
    NETWORK_COPYLEFT("network_copyleft", "네트워크 카피레프트"),

    /**
     * 저작권 포기 또는 이에 가까운 성격의 라이선스입니다.
     * 예: Unlicense, CC0
     */
    PUBLIC_DOMAIN("public_domain", "퍼블릭 도메인"),

    /**
     * 표준 SPDX 라이선스로 확정하기 어려운 자체 작성 라이선스입니다.
     */
    CUSTOM("custom", "커스텀"),

    /**
     * 근거가 부족하거나 아직 식별하지 못한 라이선스입니다.
     */
    UNKNOWN("unknown", "확인 필요");

    /**
     * JSON이나 정책 매핑에서 사용할 안정적인 코드값입니다.
     */
    private final String code;

    /**
     * 화면에서 사용자에게 보여줄 한국어 계열 이름입니다.
     */
    private final String label;
}
