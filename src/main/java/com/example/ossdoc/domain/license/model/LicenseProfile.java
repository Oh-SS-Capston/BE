package com.example.ossdoc.domain.license.model;

import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * OSSDoc이 알고 있는 표준 라이선스 프로필입니다.
 *
 * <p>역할:
 * LICENSE, README, pom.xml, build.gradle 등에서 감지한 라이선스 이름을 화면에 보여줄 수 있는
 * 표준 정보로 바꾸기 위한 기준 데이터입니다.
 *
 * <p>중요:
 * 이 클래스는 분석 결과 그 자체가 아니라 "정책 카탈로그의 한 항목"입니다.
 * 실제 분석 결과에는 이 프로필 정보와 함께 파일 위치, snippet, confidence 같은 근거 정보가 붙습니다.
 */
@Getter
@Builder
@Jacksonized
public class LicenseProfile {

    /**
     * SPDX 기준으로 정규화한 라이선스 식별자입니다.
     * 예: Apache-2.0, MIT, GPL-3.0, UNKNOWN
     */
    private String spdxId;

    /**
     * 화면에 보여줄 라이선스 이름입니다.
     * SPDX ID보다 사람이 읽기 쉬운 전체 이름을 담습니다.
     */
    private String displayName;

    /**
     * 라이선스의 큰 성격입니다.
     * 대표 라이선스 카드에서 허용형, 카피레프트, 확인 필요 같은 배지로 사용할 수 있습니다.
     */
    private LicenseFamily family;

    /**
     * 사용 전에 어느 정도 검토가 필요한지 나타내는 수준입니다.
     * 안전/위험을 단정하지 않고, 사람이 추가로 확인해야 하는 정도를 표현합니다.
     */
    private LicenseReviewLevel reviewLevel;

    /**
     * 사용자가 라이선스 의미를 빠르게 이해할 수 있는 짧은 설명입니다.
     * LLM이 생성하는 문장이 아니라, 카탈로그에 고정된 설명 문구입니다.
     */
    private String summary;

    /**
     * 해당 라이선스에서 일반적으로 허용되는 행위 목록입니다.
     * 예: 상업적 사용, 수정, 배포
     */
    private List<String> permissions;

    /**
     * 해당 라이선스를 사용할 때 지켜야 하는 주요 의무 목록입니다.
     * 예: 저작권 고지 유지, 라이선스 전문 포함
     */
    private List<String> obligations;

    /**
     * 배포 또는 도입 전에 추가로 주의해서 확인할 내용입니다.
     * 예: 소스 공개 범위 확인, 네트워크 사용 조항 확인
     */
    private List<String> notices;
}
