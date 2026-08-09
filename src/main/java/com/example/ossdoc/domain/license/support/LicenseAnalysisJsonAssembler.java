package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseDisplayPolicyJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseEvidenceJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseReviewItemJson;
import com.example.ossdoc.domain.license.artifact.output.ProjectLicenseJson;
import com.example.ossdoc.domain.license.enums.LicenseAnalysisScope;
import com.example.ossdoc.domain.license.enums.LicenseFamily;
import com.example.ossdoc.domain.license.enums.LicenseReviewLevel;
import com.example.ossdoc.domain.license.model.LicenseProfile;
import com.example.ossdoc.domain.license.model.ProjectLicenseAnalysisResult;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 대표 라이선스 선택 결과를 license_analysis.json 출력 구조로 변환합니다.
 *
 * <p>읽는 순서:
 * 1. assemble()에서 전체 조립 흐름을 봅니다.
 * 2. buildEvidenceIds()와 toEvidenceJson()에서 후보 근거가 JSON 근거로 바뀌는 방식을 봅니다.
 * 3. toProjectLicenseJson()에서 최종 대표 라이선스 카드에 들어갈 값을 봅니다.
 * 4. buildReviewItems()와 buildDisplayPolicy()에서 화면 경고/검토 항목 정책을 봅니다.
 */
@Component
@RequiredArgsConstructor
public class LicenseAnalysisJsonAssembler {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String PROJECT_TARGET_ID = "project";

    private final LicenseCatalog licenseCatalog;

    /**
     * 선택 결과를 화면용 license_analysis.json 객체로 조립합니다.
     *
     * <p>핵심 동작:
     * - 모든 후보를 LicenseEvidenceJson으로 변환합니다.
     * - 선택된 후보를 ProjectLicenseJson으로 변환합니다.
     * - UNKNOWN/충돌/HIGH 검토 수준이면 reviewItems와 displayPolicy에 경고 신호를 남깁니다.
     * - 현재 MVP 범위가 대표 라이선스 전용임을 analysisScope와 warnings에 명시합니다.
     */
    public LicenseAnalysisJson assemble(
            String runId,
            OffsetDateTime generatedAt,
            ProjectLicenseAnalysisResult result
    ) {
        ProjectLicenseAnalysisResult safeResult = safeResult(result);
        List<ProjectLicenseCandidate> candidates = safeCandidates(safeResult);
        Map<ProjectLicenseCandidate, String> evidenceIds = buildEvidenceIds(candidates);
        List<LicenseEvidenceJson> evidences = toEvidenceJsons(candidates, evidenceIds);

        ProjectLicenseJson projectLicense = toProjectLicenseJson(
                safeResult.getSelectedProfile(),
                safeResult.getSelectedCandidate(),
                evidenceIds
        );
        List<LicenseReviewItemJson> reviewItems = buildReviewItems(safeResult, evidenceIds);
        LicenseDisplayPolicyJson displayPolicy = buildDisplayPolicy(safeResult, reviewItems, evidences);

        return LicenseAnalysisJson.builder()
                .schemaVersion(SCHEMA_VERSION)
                .runId(runId)
                .analysisScope(LicenseAnalysisScope.PROJECT_LICENSE_ONLY)
                .generatedAt(generatedAt)
                .projectLicense(projectLicense)
                .displayPolicy(displayPolicy)
                .reviewItems(reviewItems)
                .evidences(evidences)
                .build();
    }

    /**
     * 후보마다 JSON 근거 ID를 부여합니다.
     * 후보 순서가 유지되도록 순차 번호를 사용해 테스트와 화면 결과를 안정화합니다.
     */
    private Map<ProjectLicenseCandidate, String> buildEvidenceIds(List<ProjectLicenseCandidate> candidates) {
        Map<ProjectLicenseCandidate, String> result = new LinkedHashMap<>();
        int index = 1;
        for (ProjectLicenseCandidate candidate : candidates) {
            result.put(candidate, String.format("license_evidence_%03d", index));
            index++;
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 내부 후보 모델을 외부 출력용 근거 JSON으로 변환합니다.
     * 여기서 LicenseEvidenceType.getCode()가 LicenseEvidenceJson.evidenceType으로 연결됩니다.
     */
    private List<LicenseEvidenceJson> toEvidenceJsons(
            List<ProjectLicenseCandidate> candidates,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        List<LicenseEvidenceJson> result = new ArrayList<>();
        for (ProjectLicenseCandidate candidate : candidates) {
            result.add(toEvidenceJson(candidate, evidenceIds.get(candidate)));
        }
        return List.copyOf(result);
    }

    /**
     * 후보 하나를 JSON 근거 하나로 변환합니다.
     * 후보에 저장된 파일 경로, 줄 번호, snippet, confidence가 화면의 근거 목록에 그대로 들어갑니다.
     */
    private LicenseEvidenceJson toEvidenceJson(ProjectLicenseCandidate candidate, String evidenceId) {
        return LicenseEvidenceJson.builder()
                .evidenceId(evidenceId)
                .evidenceType(evidenceTypeCode(candidate))
                .path(candidate.getPath())
                .startLine(candidate.getStartLine())
                .endLine(candidate.getEndLine())
                .snippet(candidate.getSnippet())
                .confidence(candidate.getConfidence())
                .attrs(evidenceAttrs(candidate))
                .build();
    }

    /**
     * 후보의 부가 정보를 evidence attrs로 옮깁니다.
     * 화면이 원하면 source, rawLicenseText, matchedSpdxId 같은 값을 근거 상세에 표시할 수 있습니다.
     */
    private Map<String, Object> evidenceAttrs(ProjectLicenseCandidate candidate) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        putIfPresent(attrs, "source", sourceCode(candidate));
        putIfPresent(attrs, "sourceLabel", sourceLabel(candidate));
        putIfPresent(attrs, "matchedSpdxId", matchedSpdxId(candidate));
        putIfPresent(attrs, "rawLicenseText", candidate.getRawLicenseText());
        putIfPresent(attrs, "note", candidate.getNote());
        return Collections.unmodifiableMap(attrs);
    }

    /**
     * 선택된 프로필과 대표 근거를 프로젝트 라이선스 카드 데이터로 변환합니다.
     */
    private ProjectLicenseJson toProjectLicenseJson(
            LicenseProfile selectedProfile,
            ProjectLicenseCandidate selectedCandidate,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        LicenseProfile profile = selectedProfile == null
                ? licenseCatalog.unknownProfile()
                : selectedProfile;

        return ProjectLicenseJson.builder()
                .spdxId(profile.getSpdxId())
                .displayName(profile.getDisplayName())
                .family(profile.getFamily())
                .reviewLevel(profile.getReviewLevel())
                .confidence(selectedCandidate == null ? 0.0 : selectedCandidate.getConfidence())
                .summary(profile.getSummary())
                .permissions(profile.getPermissions())
                .obligations(profile.getObligations())
                .notices(profile.getNotices())
                .evidenceIds(selectedEvidenceIds(selectedCandidate, evidenceIds))
                .build();
    }

    /**
     * 선택 결과에서 사용자가 확인해야 할 항목을 생성합니다.
     * UNKNOWN, 후보 충돌, HIGH 검토 수준을 서로 독립적인 카드로 남깁니다.
     */
    private List<LicenseReviewItemJson> buildReviewItems(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        List<LicenseReviewItemJson> items = new ArrayList<>();

        if (!result.isKnownCandidateFound()) {
            items.add(unknownReviewItem(result, evidenceIds));
        }
        if (result.isConflictDetected()) {
            items.add(conflictReviewItem(result, evidenceIds));
        }
        if (isHighReview(result.getSelectedProfile())) {
            items.add(highReviewItem(result, evidenceIds));
        }

        return List.copyOf(items);
    }

    private LicenseReviewItemJson unknownReviewItem(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        return LicenseReviewItemJson.builder()
                .type("PROJECT_LICENSE_UNKNOWN")
                .reviewLevel(LicenseReviewLevel.NEEDS_REVIEW)
                .title("대표 라이선스를 식별하지 못했습니다.")
                .message(result.getDecisionReason())
                .targetId(PROJECT_TARGET_ID)
                .recommendation("저장소 루트의 LICENSE, README, pom.xml, build.gradle 라이선스 문구를 직접 확인하세요.")
                .evidenceIds(selectedEvidenceIds(result, evidenceIds))
                .build();
    }

    /**
     * 서로 다른 SPDX 후보가 함께 발견되었을 때 사용자에게 보여줄 충돌 검토 항목을 만듭니다.
     * 대표 후보의 근거와 충돌 후보의 근거를 모두 연결해 비교할 수 있게 합니다.
     */
    private LicenseReviewItemJson conflictReviewItem(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        return LicenseReviewItemJson.builder()
                .type("PROJECT_LICENSE_CONFLICT")
                .reviewLevel(LicenseReviewLevel.NEEDS_REVIEW)
                .title("서로 다른 대표 라이선스 후보가 발견되었습니다.")
                .message(conflictMessage(result))
                .targetId(PROJECT_TARGET_ID)
                .recommendation("LICENSE 파일과 README 또는 빌드 파일의 라이선스 문구가 서로 일치하는지 확인하세요.")
                .evidenceIds(conflictEvidenceIds(result, evidenceIds))
                .build();
    }

    /**
     * GPL/AGPL처럼 배포 방식에 따른 추가 검토가 필요한 대표 라이선스의 검토 항목을 만듭니다.
     * 이 항목은 충돌이 없어도 사람이 결합/배포 방식을 확인해야 한다는 신호입니다.
     */
    private LicenseReviewItemJson highReviewItem(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        LicenseProfile profile = result.getSelectedProfile();
        return LicenseReviewItemJson.builder()
                .type("PROJECT_LICENSE_HIGH_REVIEW")
                .reviewLevel(LicenseReviewLevel.HIGH)
                .title(profile.getSpdxId() + " 대표 라이선스는 추가 검토가 필요합니다.")
                .message("카피레프트 계열 라이선스는 배포 방식과 결합 방식을 확인해야 합니다.")
                .targetId(PROJECT_TARGET_ID)
                .recommendation("프로젝트 도입 또는 배포 전에 라이선스 의무와 공개 범위를 수동으로 검토하세요.")
                .evidenceIds(selectedEvidenceIds(result, evidenceIds))
                .build();
    }

    /**
     * 화면 표시 정책을 계산합니다.
     * 프론트는 이 값만 보고 요약 카드, 근거 목록, 경고 배너 표시 여부를 결정할 수 있습니다.
     */
    private LicenseDisplayPolicyJson buildDisplayPolicy(
            ProjectLicenseAnalysisResult result,
            List<LicenseReviewItemJson> reviewItems,
            List<LicenseEvidenceJson> evidences
    ) {
        List<String> warnings = warnings(result);
        return LicenseDisplayPolicyJson.builder()
                .displayable(true)
                .showProjectLicenseSummary(true)
                .showReviewItems(!reviewItems.isEmpty())
                .showEvidenceList(!evidences.isEmpty())
                .showUnknownLicenseWarning(!result.isKnownCandidateFound())
                .showCopyleftWarning(isHighReview(result.getSelectedProfile()))
                .requireManualReview(result.isManualReviewRequired())
                .warnings(warnings)
                .build();
    }

    private List<String> warnings(ProjectLicenseAnalysisResult result) {
        List<String> warnings = new ArrayList<>();
        warnings.add("대표 라이선스만 분석한 결과입니다. 의존성 라이선스는 포함하지 않습니다.");

        if (!result.isKnownCandidateFound()) {
            warnings.add("대표 라이선스를 식별하지 못해 수동 확인이 필요합니다.");
        }
        if (result.isConflictDetected()) {
            warnings.add("서로 다른 라이선스 후보가 발견되어 수동 확인이 필요합니다.");
        }
        if (isHighReview(result.getSelectedProfile())) {
            warnings.add(result.getSelectedProfile().getSpdxId() + " 계열은 배포 방식에 따른 추가 검토가 필요합니다.");
        }

        return List.copyOf(warnings);
    }

    /**
     * 충돌 검토 항목에 넣을 요약 문장을 만듭니다.
     * 선택 후보와 충돌 후보의 SPDX ID를 순서대로 모아 사용자가 어떤 값들이 충돌했는지 바로 보게 합니다.
     */
    private String conflictMessage(ProjectLicenseAnalysisResult result) {
        Set<String> spdxIds = new LinkedHashSet<>();
        spdxIds.add(matchedSpdxId(result.getSelectedCandidate()));
        for (ProjectLicenseCandidate candidate : safeConflictingCandidates(result)) {
            spdxIds.add(matchedSpdxId(candidate));
        }
        spdxIds.removeIf(String::isBlank);
        return "대표 라이선스 후보가 " + String.join(", ", spdxIds) + "로 나뉘어 있습니다.";
    }

    /**
     * 분석 결과의 선택 후보가 참조하는 evidence ID만 반환합니다.
     * 선택 후보가 없거나 evidence ID를 찾지 못하면 빈 목록을 반환해 JSON 조립 실패를 막습니다.
     */
    private List<String> selectedEvidenceIds(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        return selectedEvidenceIds(result.getSelectedCandidate(), evidenceIds);
    }

    /**
     * 특정 후보가 참조하는 evidence ID만 반환합니다.
     * List.of(null)이 발생하지 않도록 null ID를 명시적으로 걸러냅니다.
     */
    private List<String> selectedEvidenceIds(
            ProjectLicenseCandidate selectedCandidate,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        if (selectedCandidate == null) {
            return List.of();
        }
        String evidenceId = evidenceIds.get(selectedCandidate);
        return evidenceId == null ? List.of() : List.of(evidenceId);
    }

    /**
     * 충돌 검토 항목이 참조해야 하는 evidence ID 목록을 만듭니다.
     * 첫 번째는 대표 후보 근거, 그 뒤에는 대표 후보와 다른 SPDX 후보들의 근거가 들어갑니다.
     */
    private List<String> conflictEvidenceIds(
            ProjectLicenseAnalysisResult result,
            Map<ProjectLicenseCandidate, String> evidenceIds
    ) {
        List<String> ids = new ArrayList<>(selectedEvidenceIds(result, evidenceIds));
        for (ProjectLicenseCandidate candidate : safeConflictingCandidates(result)) {
            String evidenceId = evidenceIds.get(candidate);
            if (evidenceId != null) {
                ids.add(evidenceId);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 라이선스 자체의 성격 때문에 높은 수준의 검토가 필요한지 판단합니다.
     * 현재 MVP에서는 HIGH 등급 또는 강한 카피레프트 계열을 화면 경고 대상으로 봅니다.
     */
    private boolean isHighReview(LicenseProfile profile) {
        if (profile == null) {
            return false;
        }
        return profile.getReviewLevel() == LicenseReviewLevel.HIGH
                || profile.getFamily() == LicenseFamily.COPYLEFT
                || profile.getFamily() == LicenseFamily.NETWORK_COPYLEFT;
    }

    /**
     * 조립할 분석 결과가 null이어도 화면 JSON을 만들 수 있게 기본 UNKNOWN 결과로 보정합니다.
     * 상위 파이프라인이 실패한 경우에도 프론트에는 동일한 JSON 구조를 내려주기 위한 방어 코드입니다.
     */
    private ProjectLicenseAnalysisResult safeResult(ProjectLicenseAnalysisResult result) {
        if (result != null) {
            return result;
        }
        return ProjectLicenseAnalysisResult.builder()
                .selectedProfile(licenseCatalog.unknownProfile())
                .selectedCandidate(null)
                .candidates(List.of())
                .conflictingCandidates(List.of())
                .knownCandidateFound(false)
                .conflictDetected(false)
                .manualReviewRequired(true)
                .decisionReason("대표 라이선스 분석 결과가 비어 있습니다.")
                .build();
    }

    /**
     * 후보 목록이 null이거나 중간에 null 후보가 섞여 있어도 이후 변환 로직이 안전하게 돌도록 정리합니다.
     */
    private List<ProjectLicenseCandidate> safeCandidates(ProjectLicenseAnalysisResult result) {
        if (result.getCandidates() == null) {
            return List.of();
        }
        return result.getCandidates().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 충돌 후보 목록을 안전하게 꺼냅니다.
     * 조립기는 파이프라인 마지막 출력 계층이므로 null 목록 때문에 전체 JSON 생성이 실패하지 않게 합니다.
     */
    private List<ProjectLicenseCandidate> safeConflictingCandidates(ProjectLicenseAnalysisResult result) {
        if (result.getConflictingCandidates() == null) {
            return List.of();
        }
        return result.getConflictingCandidates().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * JSON evidenceType에 넣을 안정적인 코드값을 꺼냅니다.
     * 후보의 evidenceType이 비어 있으면 null을 반환해 "알 수 없는 값"을 그대로 표현합니다.
     */
    private String evidenceTypeCode(ProjectLicenseCandidate candidate) {
        if (candidate.getEvidenceType() == null) {
            return null;
        }
        return candidate.getEvidenceType().getCode();
    }

    /**
     * attrs.source에 넣을 후보 출처 코드값을 꺼냅니다.
     */
    private String sourceCode(ProjectLicenseCandidate candidate) {
        if (candidate.getSource() == null) {
            return null;
        }
        return candidate.getSource().getCode();
    }

    /**
     * attrs.sourceLabel에 넣을 사람이 읽기 쉬운 후보 출처 이름을 꺼냅니다.
     */
    private String sourceLabel(ProjectLicenseCandidate candidate) {
        if (candidate.getSource() == null) {
            return null;
        }
        return candidate.getSource().getLabel();
    }

    /**
     * 후보가 매칭된 SPDX ID를 안전하게 꺼냅니다.
     * 충돌 메시지와 evidence attrs에서 같은 방식으로 사용해 표기가 흔들리지 않게 합니다.
     */
    private String matchedSpdxId(ProjectLicenseCandidate candidate) {
        if (candidate == null || candidate.getProfile() == null || candidate.getProfile().getSpdxId() == null) {
            return "";
        }
        return candidate.getProfile().getSpdxId();
    }

    /**
     * JSON attrs에는 null 값을 넣지 않습니다.
     * Map.copyOf 계열은 null 값을 허용하지 않고, 화면에서도 없는 값은 아예 생략하는 편이 해석하기 쉽습니다.
     */
    private void putIfPresent(Map<String, Object> attrs, String key, Object value) {
        if (value != null) {
            attrs.put(key, value);
        }
    }
}
