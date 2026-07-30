package com.example.ossdoc.domain.license.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 대표 라이선스 후보를 뒷받침하는 근거의 종류입니다.
 * 나중에 LicenseEvidenceJson.evidenceType 값으로 옮겨 담을 수 있도록 분석 내부에서도 명시적으로 관리합니다.
 */
@Getter
@RequiredArgsConstructor
public enum LicenseEvidenceType {

    /**
     * LICENSE 또는 LICENCE 파일의 본문에서 나온 근거입니다.
     */
    LICENSE_FILE("LICENSE_FILE", "LICENSE 파일 본문"),

    /**
     * COPYING 파일의 본문에서 나온 근거입니다.
     */
    COPYING_FILE("COPYING_FILE", "COPYING 파일 본문"),

    /**
     * NOTICE 파일의 본문에서 나온 근거입니다.
     */
    NOTICE_FILE("NOTICE_FILE", "NOTICE 파일 본문"),

    /**
     * README 파일의 라이선스 문구에서 나온 근거입니다.
     */
    README("README", "README 문서"),

    /**
     * Maven pom.xml의 라이선스 문구에서 나온 근거입니다.
     */
    POM_LICENSES("POM_LICENSES", "pom.xml 라이선스 정보"),

    /**
     * Gradle build.gradle 또는 build.gradle.kts의 라이선스 문구에서 나온 근거입니다.
     */
    GRADLE_BUILD_FILE("GRADLE_BUILD_FILE", "Gradle 빌드 파일"),

    /**
     * 라이선스 파일은 존재하지만 지원하는 SPDX 프로필로 식별하지 못한 근거입니다.
     */
    UNKNOWN_LICENSE_FILE("UNKNOWN_LICENSE_FILE", "식별하지 못한 라이선스 파일");

    /**
     * JSON evidenceType에 그대로 사용할 수 있는 코드값입니다.
     */
    private final String code;

    /**
     * 개발자와 사용자에게 보여줄 한국어 설명입니다.
     */
    private final String label;
}
