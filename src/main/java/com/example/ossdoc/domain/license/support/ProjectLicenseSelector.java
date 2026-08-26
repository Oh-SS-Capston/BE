package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import com.example.ossdoc.domain.license.model.LicenseProfile;
import com.example.ossdoc.domain.license.model.ProjectLicenseAnalysisResult;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 수집된 대표 라이선스 후보 중 최종 대표 라이선스 후보를 선택합니다.
 *
 * <p>읽는 순서:
 * 1. select()에서 전체 판단 흐름을 봅니다.
 * 2. sortByEvidenceStrength()에서 후보 우선순위를 봅니다.
 * 3. findConflicts()에서 LICENSE/README 등 서로 다른 후보가 있는지 봅니다.
 * 4. requiresManualReview()에서 수동 확인이 필요한 조건을 봅니다.
 */
@Component
@RequiredArgsConstructor
public class ProjectLicenseSelector {

    private static final String UNKNOWN_SPDX_ID = "UNKNOWN";

    private final LicenseCatalog licenseCatalog;

    /**
     * 대표 라이선스 후보 목록에서 최종 대표 후보와 검토 신호를 계산합니다.
     *
     * <p>핵심 판단 기준:
     * - 식별 가능한 SPDX 후보를 UNKNOWN 후보보다 우선합니다.
     * - LICENSE/COPYING처럼 강한 파일 출처를 README/NOTICE보다 우선합니다.
     * - 같은 출처라면 confidence가 높은 후보를 우선합니다.
     * - 서로 다른 SPDX 후보가 함께 있으면 대표 후보는 고르되 conflictDetected=true로 남깁니다.
     */
    public ProjectLicenseAnalysisResult select(List<ProjectLicenseCandidate> candidates) {
        List<ProjectLicenseCandidate> safeCandidates = candidates == null
                ? List.of()
                : candidates.stream()
                .filter(Objects::nonNull)
                .toList();

        List<ProjectLicenseCandidate> knownCandidates = safeCandidates.stream()
                .filter(this::isKnownCandidate)
                .toList();

        if (knownCandidates.isEmpty()) {
            ProjectLicenseCandidate selectedUnknown = sortByEvidenceStrength(safeCandidates).stream()
                    .findFirst()
                    .orElse(null);

            return ProjectLicenseAnalysisResult.builder()
                    .selectedProfile(licenseCatalog.unknownProfile())
                    .selectedCandidate(selectedUnknown)
                    .candidates(safeCandidates)
                    .conflictingCandidates(List.of())
                    .knownCandidateFound(false)
                    .conflictDetected(false)
                    .manualReviewRequired(true)
                    .decisionReason(noKnownCandidateReason(selectedUnknown))
                    .build();
        }

        ProjectLicenseCandidate selected = sortByEvidenceStrength(knownCandidates).get(0);
        List<ProjectLicenseCandidate> conflicts = findConflicts(selected, knownCandidates);
        boolean conflictDetected = !conflicts.isEmpty();
        boolean manualReviewRequired = requiresManualReview(selected.getProfile(), conflictDetected);

        return ProjectLicenseAnalysisResult.builder()
                .selectedProfile(selected.getProfile())
                .selectedCandidate(selected)
                .candidates(safeCandidates)
                .conflictingCandidates(conflicts)
                .knownCandidateFound(true)
                .conflictDetected(conflictDetected)
                .manualReviewRequired(manualReviewRequired)
                .decisionReason(decisionReason(selected, conflictDetected))
                .build();
    }

    /**
     * 후보를 근거 강도 순서로 정렬합니다.
     * 소스 우선순위가 먼저이고, 같은 소스라면 confidence와 경로를 사용해 결과 순서를 안정화합니다.
     */
    private List<ProjectLicenseCandidate> sortByEvidenceStrength(List<ProjectLicenseCandidate> candidates) {
        List<ProjectLicenseCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((ProjectLicenseCandidate candidate) -> sourcePriority(candidate.getSource()))
                .reversed()
                .thenComparing(Comparator
                        .comparingDouble((ProjectLicenseCandidate candidate) -> safeConfidence(candidate))
                        .reversed())
                .thenComparing(candidate -> nullToEmpty(candidate.getPath()))
                .thenComparing(this::safeSpdxId));
        return sorted;
    }

    /**
     * 선택된 후보와 다른 SPDX ID를 가진 후보만 충돌 후보로 분리합니다.
     */
    private List<ProjectLicenseCandidate> findConflicts(
            ProjectLicenseCandidate selected,
            List<ProjectLicenseCandidate> knownCandidates
    ) {
        String selectedSpdxId = selected.getProfile().getSpdxId();
        return knownCandidates.stream()
                .filter(candidate -> !Objects.equals(candidate.getProfile().getSpdxId(), selectedSpdxId))
                .toList();
    }

    /**
     * 화면에서 수동 확인 경고를 띄워야 하는지 판단합니다.
     * UNKNOWN, 후보 충돌, HIGH 검토 수준, 카피레프트 계열은 사용자의 명시적 확인이 필요합니다.
     */
    private boolean requiresManualReview(LicenseProfile profile, boolean conflictDetected) {
        if (profile == null || conflictDetected) {
            return true;
        }
        return profile.getReviewLevel() == LicenseReviewLevel.NEEDS_REVIEW
                || profile.getReviewLevel() == LicenseReviewLevel.HIGH
                || profile.getFamily() == LicenseFamily.COPYLEFT
                || profile.getFamily() == LicenseFamily.NETWORK_COPYLEFT;
    }

    /**
     * 출처별 우선순위입니다.
     * 값이 높을수록 대표 라이선스 판단에서 강한 근거로 봅니다.
     */
    private int sourcePriority(LicenseCandidateSource source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case LICENSE_FILE -> 100;
            case COPYING_FILE -> 95;
            case MAVEN_POM -> 80;
            case GRADLE_BUILD_FILE -> 75;
            case README_FILE -> 65;
            case NOTICE_FILE -> 55;
        };
    }

    /**
     * 후보가 UNKNOWN이 아닌 SPDX 프로필을 가지고 있는지 확인합니다.
     */
    private boolean isKnownCandidate(ProjectLicenseCandidate candidate) {
        if (candidate == null || candidate.getProfile() == null) {
            return false;
        }
        return !UNKNOWN_SPDX_ID.equals(candidate.getProfile().getSpdxId());
    }

    /**
     * confidence가 비어 있는 후보를 정렬할 때 0점 근거로 취급합니다.
     * 이렇게 해야 일부 후보의 confidence가 null이어도 대표 후보 선택 과정이 중단되지 않습니다.
     */
    private double safeConfidence(ProjectLicenseCandidate candidate) {
        return candidate.getConfidence() == null ? 0.0 : candidate.getConfidence();
    }

    /**
     * 정렬 비교용 문자열을 안전하게 만듭니다.
     * null을 빈 문자열로 바꿔 path나 SPDX ID가 없어도 정렬 결과가 항상 결정되게 합니다.
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 후보의 SPDX ID를 정렬용 문자열로 꺼냅니다.
     * 같은 출처와 confidence를 가진 후보가 여러 개일 때 마지막 tie-breaker로 사용합니다.
     */
    private String safeSpdxId(ProjectLicenseCandidate candidate) {
        if (candidate.getProfile() == null) {
            return "";
        }
        return nullToEmpty(candidate.getProfile().getSpdxId());
    }

    /**
     * 식별 가능한 후보가 하나도 없을 때 남길 판단 사유 문장을 만듭니다.
     * 라이선스 관련 파일 자체가 없는 경우와, 파일은 있지만 UNKNOWN인 경우를 구분합니다.
     */
    private String noKnownCandidateReason(ProjectLicenseCandidate selectedUnknown) {
        if (selectedUnknown == null) {
            return "대표 라이선스 후보를 찾지 못했습니다.";
        }
        return "라이선스 관련 파일은 발견했지만 지원하는 SPDX 라이선스로 식별하지 못했습니다.";
    }

    /**
     * 최종 선택 결과를 로그/화면/테스트에서 읽을 수 있는 짧은 판단 사유로 만듭니다.
     * 대표 후보를 고른 근거 출처와 충돌 여부를 한 문장 안에 담습니다.
     */
    private String decisionReason(ProjectLicenseCandidate selected, boolean conflictDetected) {
        String sourceLabel = selected.getSource() == null ? "라이선스 후보" : selected.getSource().getLabel();
        String base = sourceLabel + " 근거를 기준으로 "
                + safeSpdxId(selected) + "을 대표 라이선스 후보로 선택했습니다.";
        if (!conflictDetected) {
            return base;
        }

        return base + " 다만 다른 파일에서 상이한 라이선스 후보가 발견되어 확인이 필요합니다.";
    }
}
