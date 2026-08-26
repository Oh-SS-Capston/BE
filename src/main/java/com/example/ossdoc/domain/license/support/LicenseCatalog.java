package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import com.example.ossdoc.domain.license.model.LicenseProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 대표 라이선스 MVP에서 사용하는 SPDX 기반 라이선스 카탈로그입니다.
 *
 * <p>역할:
 * 스캐너가 LICENSE, README, pom.xml, build.gradle에서 감지한 문자열을 표준 라이선스 프로필로 변환합니다.
 * 예를 들어 "Apache License Version 2.0"을 Apache-2.0 프로필로 정규화합니다.
 *
 * <p>설계 이유:
 * 파일을 읽는 스캐너 안에 라이선스 판단 규칙을 직접 넣으면 README/POM/Gradle 스캐너마다 중복이 생깁니다.
 * 그래서 "라이선스 이름 또는 문장 -> 표준 프로필" 변환 규칙을 이 클래스에 모읍니다.
 *
 * <p>조회 흐름:
 * 표준 SPDX ID는 profilesBySpdxKey로 먼저 직접 조회합니다.
 * SPDX ID가 아닌 표시명/별칭/본문 문구는 비교용 텍스트 키로 정규화한 뒤 별칭 인덱스에서 조회합니다.
 */
@Component
public class LicenseCatalog {

    private static final String UNKNOWN_SPDX_ID = "UNKNOWN";

    /**
     * SPDX ID로 프로필을 찾기 위한 인덱스입니다.
     * 키는 대소문자 차이로 인한 조회 실패를 줄이기 위해 소문자로 정규화합니다.
     */
    private final Map<String, LicenseProfile> profilesBySpdxKey;

    /**
     * 라이선스 이름이나 별칭을 비교용 텍스트 키로 정규화한 뒤 SPDX ID를 찾기 위한 인덱스입니다.
     * 예: "Apache License Version 2.0", "Apache License 2.0" 모두 Apache-2.0으로 연결됩니다.
     * 표준 SPDX ID 자체는 이 인덱스에 넣지 않고 profilesBySpdxKey에서 먼저 직접 조회합니다.
     */
    private final Map<String, String> spdxIdByLicenseTextKey;

    /**
     * 긴 본문에서 라이선스 문구를 찾기 위한 매칭 규칙입니다.
     * README 문장이나 LICENSE 전문 일부를 대상으로 사용할 수 있습니다.
     */
    private final List<TextMarker> textMarkers;

    public LicenseCatalog() {
        List<CatalogEntry> entries = buildEntries();
        this.profilesBySpdxKey = buildProfileIndex(entries);
        this.spdxIdByLicenseTextKey = buildAliasIndex(entries);
        this.textMarkers = buildTextMarkers(entries);
    }

    /**
     * 카탈로그가 알고 있는 모든 라이선스 프로필을 반환합니다.
     * 화면이나 테스트에서 지원 범위를 확인할 때 사용할 수 있습니다.
     */
    public List<LicenseProfile> supportedProfiles() {
        return List.copyOf(profilesBySpdxKey.values());
    }

    /**
     * SPDX ID로 라이선스 프로필을 조회합니다.
     * 알 수 없는 ID면 Optional.empty()를 반환해 호출자가 UNKNOWN 처리 여부를 결정하게 합니다.
     */
    public Optional<LicenseProfile> findBySpdxId(String spdxId) {
        String key = toSpdxKey(spdxId);
        if (key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profilesBySpdxKey.get(key));
    }

    /**
     * SPDX ID로 라이선스 프로필을 조회하고, 없으면 UNKNOWN 프로필을 반환합니다.
     * 분석 결과를 만들 때 null 분기를 줄이고 싶을 때 사용합니다.
     */
    public LicenseProfile resolveSpdxIdOrUnknown(String spdxId) {
        return findBySpdxId(spdxId).orElseGet(this::unknownProfile);
    }

    /**
     * 라이선스 이름 또는 짧은 별칭을 표준 프로필로 정규화합니다.
     * 먼저 표준 SPDX ID로 직접 조회하고, 실패하면 표시명/별칭 인덱스를 조회합니다.
     * 예: "Apache-2.0" -> Apache-2.0, "Apache License, Version 2.0" -> Apache-2.0
     */
    public LicenseProfile resolveNameOrUnknown(String rawName) {
        Optional<LicenseProfile> spdxProfile = findBySpdxId(rawName);
        if (spdxProfile.isPresent()) {
            return spdxProfile.get();
        }

        String licenseTextKey = toLicenseTextKey(rawName);
        if (licenseTextKey.isBlank()) {
            return unknownProfile();
        }

        String spdxId = spdxIdByLicenseTextKey.get(licenseTextKey);
        if (spdxId == null) {
            return unknownProfile();
        }
        return resolveSpdxIdOrUnknown(spdxId);
    }

    /**
     * README 문장이나 LICENSE 본문처럼 긴 텍스트에서 라이선스 문구를 찾아 프로필로 변환합니다.
     * 긴 문구를 먼저 검사해 "GNU Lesser GPL"이 단순 "GPL"로 잘못 분류되는 상황을 줄입니다.
     */
    public LicenseProfile resolveTextOrUnknown(String text) {
        String textKey = toLicenseTextKey(text);
        if (textKey.isBlank()) {
            return unknownProfile();
        }

        LicenseProfile exact = resolveNameOrUnknown(text);
        if (!UNKNOWN_SPDX_ID.equals(exact.getSpdxId())) {
            return exact;
        }

        for (TextMarker marker : textMarkers) {
            if (textKey.contains(marker.markerKey())) {
                return resolveSpdxIdOrUnknown(marker.spdxId());
            }
        }

        return unknownProfile();
    }

    /**
     * UNKNOWN 프로필을 반환합니다.
     * 라이선스를 식별하지 못했거나 아직 지원하지 않는 라이선스일 때 사용합니다.
     */
    public LicenseProfile unknownProfile() {
        return profilesBySpdxKey.get(toSpdxKey(UNKNOWN_SPDX_ID));
    }

    private Map<String, LicenseProfile> buildProfileIndex(List<CatalogEntry> entries) {
        Map<String, LicenseProfile> result = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            result.put(toSpdxKey(entry.profile().getSpdxId()), entry.profile());
        }
        return Map.copyOf(result);
    }

    private Map<String, String> buildAliasIndex(List<CatalogEntry> entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            // SPDX ID는 이미 profilesBySpdxKey의 책임이므로 여기에는 displayName과 aliases만 등록합니다.
            result.put(toLicenseTextKey(entry.profile().getDisplayName()), entry.profile().getSpdxId());
            for (String alias : entry.aliases()) {
                result.put(toLicenseTextKey(alias), entry.profile().getSpdxId());
            }
        }
        return Map.copyOf(result);
    }

    private List<TextMarker> buildTextMarkers(List<CatalogEntry> entries) {
        List<TextMarker> result = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            for (String marker : entry.textMarkers()) {
                String markerKey = toLicenseTextKey(marker);
                if (!markerKey.isBlank()) {
                    result.add(new TextMarker(markerKey, entry.profile().getSpdxId()));
                }
            }
        }
        result.sort(Comparator.comparingInt((TextMarker marker) -> marker.markerKey().length()).reversed());
        return List.copyOf(result);
    }

    private String toSpdxKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 라이선스 표시명, 별칭, README 문장 일부를 비교하기 쉬운 텍스트 키로 정규화합니다.
     * SPDX ID 직접 조회용 메서드가 아니라, 대소문자/구두점/공백 차이를 제거한 비교용 키를 만드는 메서드입니다.
     * 표준 SPDX ID는 이 메서드를 거치기 전에 findBySpdxId에서 먼저 처리합니다.
     *
     * <p>예:
     * "Apache License, Version 2.0" -> "apache license version 2 0"
     * "The MIT License" -> "the mit license"
     */
    private String toLicenseTextKey(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private List<CatalogEntry> buildEntries() {
        return List.of(
                entry(
                        profile(
                                "Apache-2.0",
                                "Apache License 2.0",
                                LicenseFamily.PERMISSIVE,
                                LicenseReviewLevel.LOW,
                                "상업적 사용, 수정, 배포가 비교적 자유로운 허용형 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포", "특허 사용"),
                                List.of("저작권 고지 유지", "라이선스 전문 포함", "변경 사항 표시"),
                                List.of("NOTICE 파일이 있으면 배포 시 함께 유지해야 합니다.")
                        ),
                        List.of("Apache License Version 2.0", "Apache License, Version 2.0", "Apache 2.0"),
                        List.of("Apache License Version 2.0", "Apache License, Version 2.0")
                ),
                entry(
                        profile(
                                "MIT",
                                "MIT License",
                                LicenseFamily.PERMISSIVE,
                                LicenseReviewLevel.LOW,
                                "짧고 단순한 허용형 라이선스로, 고지 유지 조건을 중심으로 검토합니다.",
                                List.of("상업적 사용", "수정", "배포", "사적 사용"),
                                List.of("저작권 고지 유지", "라이선스 전문 포함"),
                                List.of("배포물에 저작권 및 라이선스 문구가 유지되는지 확인해야 합니다.")
                        ),
                        List.of("The MIT License"),
                        List.of("MIT License", "The MIT License")
                ),
                entry(
                        profile(
                                "BSD-2-Clause",
                                "BSD 2-Clause License",
                                LicenseFamily.PERMISSIVE,
                                LicenseReviewLevel.LOW,
                                "고지 유지 조건을 중심으로 하는 허용형 BSD 계열 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("저작권 고지 유지", "라이선스 전문 포함"),
                                List.of("배포물에 BSD 고지 문구가 유지되는지 확인해야 합니다.")
                        ),
                        List.of("Simplified BSD License", "FreeBSD License"),
                        List.of("BSD 2-Clause License", "Redistribution and use in source and binary forms")
                ),
                entry(
                        profile(
                                "BSD-3-Clause",
                                "BSD 3-Clause License",
                                LicenseFamily.PERMISSIVE,
                                LicenseReviewLevel.LOW,
                                "고지 유지와 이름 사용 제한 조건을 포함하는 허용형 BSD 계열 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("저작권 고지 유지", "라이선스 전문 포함", "기여자 이름 홍보 사용 제한"),
                                List.of("프로젝트나 기여자 이름을 홍보 목적으로 사용할 때 제한 조항을 확인해야 합니다.")
                        ),
                        List.of("New BSD License", "Modified BSD License"),
                        List.of("BSD 3-Clause License", "Neither the name of the copyright holder")
                ),
                entry(
                        profile(
                                "MPL-2.0",
                                "Mozilla Public License 2.0",
                                LicenseFamily.WEAK_COPYLEFT,
                                LicenseReviewLevel.MEDIUM,
                                "파일 단위 공개 의무를 검토해야 하는 약한 카피레프트 계열 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "수정한 MPL 적용 파일의 소스 공개"),
                                List.of("MPL 적용 파일을 수정해 배포하는 경우 공개 범위를 확인해야 합니다.")
                        ),
                        List.of("Mozilla Public License Version 2.0"),
                        List.of("Mozilla Public License Version 2.0", "Mozilla Public License 2.0")
                ),
                entry(
                        profile(
                                "EPL-2.0",
                                "Eclipse Public License 2.0",
                                LicenseFamily.WEAK_COPYLEFT,
                                LicenseReviewLevel.MEDIUM,
                                "배포 방식에 따라 소스 제공 의무를 확인해야 하는 약한 카피레프트 계열 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "수정 소스 제공 조건 확인"),
                                List.of("EPL 적용 코드 수정 및 배포 시 공개 범위를 확인해야 합니다.")
                        ),
                        List.of("Eclipse Public License Version 2.0"),
                        List.of("Eclipse Public License Version 2.0", "Eclipse Public License 2.0")
                ),
                entry(
                        profile(
                                "LGPL-2.1",
                                "GNU Lesser General Public License v2.1",
                                LicenseFamily.WEAK_COPYLEFT,
                                LicenseReviewLevel.MEDIUM,
                                "라이브러리 결합 방식에 따라 공개 의무 검토가 필요한 약한 카피레프트 계열 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "수정한 라이브러리 소스 공개 조건 확인"),
                                List.of("정적 링크/동적 링크 등 결합 방식을 확인해야 합니다.")
                        ),
                        List.of("GNU Lesser General Public License Version 2.1", "Lesser GPL 2.1"),
                        List.of("GNU Lesser General Public License Version 2.1", "GNU Lesser General Public License")
                ),
                entry(
                        profile(
                                "LGPL-3.0",
                                "GNU Lesser General Public License v3.0",
                                LicenseFamily.WEAK_COPYLEFT,
                                LicenseReviewLevel.MEDIUM,
                                "LGPL v3 계열로, 라이브러리 결합 방식과 추가 조항을 함께 확인해야 합니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "수정한 라이브러리 소스 공개 조건 확인"),
                                List.of("제품 배포 시 사용자가 라이브러리를 교체할 수 있는 조건을 확인해야 합니다.")
                        ),
                        List.of("GNU Lesser General Public License Version 3.0", "Lesser GPL 3.0"),
                        List.of("GNU Lesser General Public License Version 3.0", "GNU Lesser General Public License Version 3")
                ),
                entry(
                        profile(
                                "GPL-2.0",
                                "GNU General Public License v2.0",
                                LicenseFamily.COPYLEFT,
                                LicenseReviewLevel.HIGH,
                                "배포와 결합 방식에 따라 강한 소스 공개 의무가 발생할 수 있는 카피레프트 계열 라이선스입니다.",
                                List.of("사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "배포 시 소스 공개 조건 확인"),
                                List.of("프로젝트 결합 방식과 배포 형태를 반드시 검토해야 합니다.")
                        ),
                        List.of("GNU General Public License Version 2.0", "GPL v2"),
                        List.of("GNU General Public License Version 2.0", "GNU General Public License Version 2")
                ),
                entry(
                        profile(
                                "GPL-3.0",
                                "GNU General Public License v3.0",
                                LicenseFamily.COPYLEFT,
                                LicenseReviewLevel.HIGH,
                                "배포와 결합 방식에 따라 강한 소스 공개 의무가 발생할 수 있는 카피레프트 계열 라이선스입니다.",
                                List.of("사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "배포 시 소스 공개 조건 확인"),
                                List.of("제품 배포, 결합 방식, 특허 관련 조항을 함께 검토해야 합니다.")
                        ),
                        List.of("GNU General Public License Version 3.0", "GPL v3"),
                        List.of("GNU General Public License Version 3.0", "GNU General Public License Version 3")
                ),
                entry(
                        profile(
                                "AGPL-3.0",
                                "GNU Affero General Public License v3.0",
                                LicenseFamily.NETWORK_COPYLEFT,
                                LicenseReviewLevel.HIGH,
                                "네트워크 서비스 제공 형태에서도 소스 공개 검토가 필요한 강한 카피레프트 계열 라이선스입니다.",
                                List.of("사용", "수정", "배포"),
                                List.of("라이선스 전문 포함", "네트워크 사용 시 소스 제공 조건 확인"),
                                List.of("웹 서비스나 API 서버 형태로 제공할 때도 조항을 반드시 확인해야 합니다.")
                        ),
                        List.of("GNU Affero General Public License Version 3.0", "Affero GPL v3"),
                        List.of("GNU Affero General Public License Version 3.0", "GNU Affero General Public License Version 3")
                ),
                entry(
                        profile(
                                "CC0-1.0",
                                "Creative Commons Zero v1.0 Universal",
                                LicenseFamily.PUBLIC_DOMAIN,
                                LicenseReviewLevel.LOW,
                                "저작권 포기에 가까운 퍼블릭 도메인 성격의 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("일반적으로 별도 의무가 적지만 원문 조건 확인 필요"),
                                List.of("코드 라이선스가 아니라 데이터/문서에 쓰이는 경우도 있으므로 적용 대상을 확인해야 합니다.")
                        ),
                        List.of("Creative Commons Zero", "CC0"),
                        List.of("Creative Commons Zero", "CC0 1.0 Universal")
                ),
                entry(
                        profile(
                                "Unlicense",
                                "The Unlicense",
                                LicenseFamily.PUBLIC_DOMAIN,
                                LicenseReviewLevel.LOW,
                                "저작권 포기에 가까운 매우 자유로운 성격의 라이선스입니다.",
                                List.of("상업적 사용", "수정", "배포"),
                                List.of("원문 조건 확인"),
                                List.of("조직 정책상 퍼블릭 도메인 계열 허용 여부를 확인할 수 있습니다.")
                        ),
                        List.of("The Unlicense"),
                        List.of("The Unlicense")
                ),
                entry(
                        profile(
                                UNKNOWN_SPDX_ID,
                                "UNKNOWN",
                                LicenseFamily.UNKNOWN,
                                LicenseReviewLevel.NEEDS_REVIEW,
                                "라이선스를 식별하지 못했거나 OSSDoc이 아직 지원하지 않는 라이선스입니다.",
                                List.of(),
                                List.of("사람이 직접 라이선스 근거를 확인해야 합니다."),
                                List.of("LICENSE, README, 빌드 파일의 라이선스 문구를 수동으로 확인하세요.")
                        ),
                        List.of("Unknown License", "No License"),
                        List.of()
                )
        );
    }

    private CatalogEntry entry(LicenseProfile profile, List<String> aliases, List<String> textMarkers) {
        return new CatalogEntry(profile, aliases, textMarkers);
    }

    private LicenseProfile profile(
            String spdxId,
            String displayName,
            LicenseFamily family,
            LicenseReviewLevel reviewLevel,
            String summary,
            List<String> permissions,
            List<String> obligations,
            List<String> notices
    ) {
        return LicenseProfile.builder()
                .spdxId(spdxId)
                .displayName(displayName)
                .family(family)
                .reviewLevel(reviewLevel)
                .summary(summary)
                .permissions(permissions)
                .obligations(obligations)
                .notices(notices)
                .build();
    }

    private record CatalogEntry(
            LicenseProfile profile,
            List<String> aliases,
            List<String> textMarkers
    ) {
    }

    private record TextMarker(
            String markerKey,
            String spdxId
    ) {
    }
}
