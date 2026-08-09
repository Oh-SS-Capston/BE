package com.example.ossdoc.domain.license.model;

import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseEvidenceType;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 저장소에서 발견한 대표 라이선스 후보 하나를 표현합니다.
 *
 * <p>역할:
 * 이 객체는 최종 대표 라이선스 결론이 아닙니다.
 * LICENSE, README, pom.xml, build.gradle 등 여러 파일에서 발견한 후보와 근거를 그대로 담고,
 * 다음 커밋의 선택 로직이 가장 신뢰할 수 있는 후보를 고르도록 전달합니다.
 */
@Getter
@Builder
@Jacksonized
public class ProjectLicenseCandidate {

    /**
     * 후보가 가리키는 표준 라이선스 프로필입니다.
     * 식별하지 못한 경우에도 UNKNOWN 프로필을 넣어 호출자가 null 없이 처리할 수 있게 합니다.
     */
    private LicenseProfile profile;

    /**
     * 후보가 발견된 파일 유형입니다.
     * 예: LICENSE_FILE, README_FILE, MAVEN_POM
     */
    private LicenseCandidateSource source;

    /**
     * 후보 판단에 사용된 근거 유형입니다.
     * 나중에 LicenseEvidenceJson.evidenceType으로 변환할 수 있습니다.
     */
    private LicenseEvidenceType evidenceType;

    /**
     * 저장소 루트 기준의 상대 파일 경로입니다.
     * 예: LICENSE, README.md, pom.xml
     */
    private String path;

    /**
     * 후보를 감지한 시작 줄 번호입니다.
     * 줄 번호는 사용자가 근거 위치를 바로 확인할 수 있도록 1부터 시작합니다.
     */
    private Integer startLine;

    /**
     * 후보를 감지한 마지막 줄 번호입니다.
     * 여러 줄을 합쳐 감지한 경우 startLine보다 클 수 있습니다.
     */
    private Integer endLine;

    /**
     * 후보 감지에 사용한 짧은 원문 조각입니다.
     * 너무 긴 파일 전체가 아니라 화면과 로그에서 확인할 수 있는 수준의 snippet만 담습니다.
     */
    private String snippet;

    /**
     * 이 후보가 어떤 원문 라이선스 문구에서 나왔는지 보여주는 값입니다.
     * 대부분 snippet과 같지만, 나중에 XML/Gradle 파서가 원문 이름만 뽑으면 그 이름을 담을 수 있습니다.
     */
    private String rawLicenseText;

    /**
     * 후보 근거의 강도입니다.
     * LICENSE 파일의 명확한 문구는 높고, README나 NOTICE의 보조 문구는 상대적으로 낮게 둡니다.
     */
    private Double confidence;

    /**
     * 후보를 해석할 때 도움이 되는 짧은 설명입니다.
     * 예: LICENSE 파일에서 Apache-2.0 문구를 감지했습니다.
     */
    private String note;
}
