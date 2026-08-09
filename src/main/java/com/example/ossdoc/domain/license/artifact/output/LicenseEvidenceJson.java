package com.example.ossdoc.domain.license.artifact.output;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * 라이선스 판단에 사용된 근거입니다.
 * 대표 라이선스 판단과 검토 필요 항목은 이 근거를 ID로 참조해야 합니다.
 */
@Getter
@Builder
@Jacksonized
public class LicenseEvidenceJson {

    /**
     * 근거를 식별하기 위한 ID입니다.
     * 다른 JSON 항목의 evidenceIds에서 이 값을 참조합니다.
     */
    private String evidenceId;

    /**
     * 근거의 종류입니다.
     * 예: LICENSE_FILE, README, POM_LICENSES, GRADLE_BUILD_FILE
     */
    private String evidenceType;

    /**
     * 근거가 발견된 파일 경로입니다.
     * 대표 라이선스 MVP에서는 저장소 내부 파일의 repo 상대 경로를 담습니다.
     * 예: LICENSE, README.md, pom.xml, build.gradle
     */
    private String path;

    /**
     * 근거가 시작되는 줄 번호입니다.
     * 줄 단위 위치를 모르면 null일 수 있습니다.
     */
    private Integer startLine;

    /**
     * 근거가 끝나는 줄 번호입니다.
     * 한 줄 근거라면 startLine과 같은 값이 들어갑니다.
     */
    private Integer endLine;

    /**
     * 판단에 사용한 짧은 코드 또는 문서 일부입니다.
     * 화면에서 근거를 확인할 수 있도록 너무 길지 않은 문장만 담습니다.
     */
    private String snippet;

    /**
     * 이 근거만 보았을 때 라이선스 판단에 얼마나 강하게 기여하는지 나타냅니다.
     * 1.0에 가까울수록 강한 근거입니다.
     */
    private Double confidence;

    /**
     * 근거와 관련된 부가 정보입니다.
     * 예: 원본 라이선스 이름, 매칭된 SPDX 후보, 매칭 방식
     */
    private Map<String, Object> attrs;
}
