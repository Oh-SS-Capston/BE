package com.example.ossdoc.domain.license.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 대표 라이선스 후보가 어떤 파일 유형에서 발견되었는지 나타냅니다.
 * 다음 단계의 대표 라이선스 선택 로직은 이 값을 기준으로 LICENSE 파일 근거를 README 근거보다 강하게 평가할 수 있습니다.
 */
@Getter
@RequiredArgsConstructor
public enum LicenseCandidateSource {

    /**
     * 저장소 루트의 LICENSE 또는 LICENCE 파일에서 발견된 후보입니다.
     * 일반적으로 대표 라이선스 판단에서 가장 강한 근거로 봅니다.
     */
    LICENSE_FILE("license_file", "LICENSE 파일"),

    /**
     * 저장소 루트의 COPYING 파일에서 발견된 후보입니다.
     * GPL 계열 프로젝트에서 대표 라이선스 전문을 담는 경우가 많아 강한 근거로 봅니다.
     */
    COPYING_FILE("copying_file", "COPYING 파일"),

    /**
     * 저장소 루트의 NOTICE 파일에서 발견된 후보입니다.
     * NOTICE는 고지 문서 성격이 강하므로 LICENSE 파일보다 낮은 근거로 봅니다.
     */
    NOTICE_FILE("notice_file", "NOTICE 파일"),

    /**
     * README 파일에서 발견된 후보입니다.
     * 사용자 설명 문서에 적힌 라이선스 문구이므로 보조 근거로 사용합니다.
     */
    README_FILE("readme_file", "README 파일"),

    /**
     * Maven pom.xml의 licenses 영역 또는 라이선스 문구에서 발견된 후보입니다.
     * 빌드 메타데이터에 명시된 라이선스이므로 보조 근거로 사용합니다.
     */
    MAVEN_POM("maven_pom", "Maven pom.xml"),

    /**
     * Gradle build.gradle 또는 build.gradle.kts에서 발견된 후보입니다.
     * 프로젝트 메타데이터나 배포 설정에 들어간 라이선스 문구를 보조 근거로 사용합니다.
     */
    GRADLE_BUILD_FILE("gradle_build_file", "Gradle build file");

    /**
     * JSON이나 attrs에 저장할 안정적인 코드값입니다.
     */
    private final String code;

    /**
     * 로그, 테스트, 화면 설명에서 사람이 읽을 수 있는 한국어 이름입니다.
     */
    private final String label;
}
