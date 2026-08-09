package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import com.example.ossdoc.domain.license.model.LicenseProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseCatalogTest {

    private final LicenseCatalog catalog = new LicenseCatalog();

    @Test
    @DisplayName("SPDX ID로 라이선스 프로필을 조회할 수 있다")
    void findBySpdxId_returnsProfile() {
        LicenseProfile profile = catalog.resolveSpdxIdOrUnknown("Apache-2.0");

        assertThat(profile.getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(profile.getDisplayName()).isEqualTo("Apache License 2.0");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.PERMISSIVE);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.LOW);
    }

    @Test
    @DisplayName("라이선스 이름 정규화는 먼저 SPDX ID 직접 조회를 사용한다")
    void resolveNameOrUnknown_usesSpdxLookupFirst() {
        LicenseProfile profile = catalog.resolveNameOrUnknown("Apache-2.0");

        assertThat(profile.getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(profile.getDisplayName()).isEqualTo("Apache License 2.0");
    }

    @Test
    @DisplayName("대소문자와 구두점이 달라도 Apache 2.0 이름을 SPDX ID로 정규화한다")
    void resolveNameOrUnknown_normalizesApacheAlias() {
        LicenseProfile profile = catalog.resolveNameOrUnknown("Apache License, Version 2.0");

        assertThat(profile.getSpdxId()).isEqualTo("Apache-2.0");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.PERMISSIVE);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.LOW);
    }

    @Test
    @DisplayName("README 문장처럼 긴 텍스트 안에서도 MIT 라이선스 문구를 찾는다")
    void resolveTextOrUnknown_detectsMitInSentence() {
        LicenseProfile profile = catalog.resolveTextOrUnknown(
                "This project is licensed under the MIT License."
        );

        assertThat(profile.getSpdxId()).isEqualTo("MIT");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.PERMISSIVE);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.LOW);
    }

    @Test
    @DisplayName("GPL v3 계열은 높은 검토 수준으로 분류한다")
    void resolveNameOrUnknown_classifiesGplAsHighReview() {
        LicenseProfile profile = catalog.resolveNameOrUnknown("GNU General Public License Version 3.0");

        assertThat(profile.getSpdxId()).isEqualTo("GPL-3.0");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.COPYLEFT);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.HIGH);
    }

    @Test
    @DisplayName("AGPL 계열은 네트워크 카피레프트와 높은 검토 수준으로 분류한다")
    void resolveTextOrUnknown_classifiesAgplAsNetworkCopyleft() {
        LicenseProfile profile = catalog.resolveTextOrUnknown(
                "Licensed under the GNU Affero General Public License Version 3.0."
        );

        assertThat(profile.getSpdxId()).isEqualTo("AGPL-3.0");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.NETWORK_COPYLEFT);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.HIGH);
    }

    @Test
    @DisplayName("알 수 없는 문자열은 UNKNOWN 프로필로 처리한다")
    void resolveNameOrUnknown_returnsUnknownProfile() {
        LicenseProfile profile = catalog.resolveNameOrUnknown("Some Internal Company License");

        assertThat(profile.getSpdxId()).isEqualTo("UNKNOWN");
        assertThat(profile.getFamily()).isEqualTo(LicenseFamily.UNKNOWN);
        assertThat(profile.getReviewLevel()).isEqualTo(LicenseReviewLevel.NEEDS_REVIEW);
    }
}
