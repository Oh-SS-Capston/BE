package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.model.ProjectLicenseAnalysisResult;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import com.example.ossdoc.domain.license.support.LicenseAnalysisJsonAssembler;
import com.example.ossdoc.domain.license.support.ProjectLicenseCandidateCollector;
import com.example.ossdoc.domain.license.support.ProjectLicenseSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대표 라이선스 분석을 독립적으로 실행하는 서비스입니다.
 *
 * <p>역할:
 * 지금까지 만든 collector, selector, assembler를 한 흐름으로 연결합니다.
 * 단, 이 서비스는 아직 파이프라인, Artifact 저장, Controller와 연결하지 않습니다.
 *
 * <p>현재 실행 흐름:
 * 1. repoRoot에서 대표 라이선스 후보를 수집합니다.
 * 2. 수집된 후보 중 대표 후보를 선택하고 충돌/검토 필요 신호를 계산합니다.
 * 3. 선택 결과를 화면에서 사용할 LicenseAnalysisJson으로 조립합니다.
 *
 * <p>나중에 파이프라인을 연결할 때:
 * RunPipelineExecutor 또는 별도 publisher가 이 서비스의 analyze(runId, repoRoot)를 호출하고,
 * 반환된 LicenseAnalysisJson을 ArtifactService로 저장하면 됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseAnalysisService {

    private final ProjectLicenseCandidateCollector candidateCollector;
    private final ProjectLicenseSelector projectLicenseSelector;
    private final LicenseAnalysisJsonAssembler jsonAssembler;

    /**
     * 저장소 루트 기준으로 대표 라이선스 분석 JSON을 생성합니다.
     *
     * <p>중요:
     * repoRoot가 null이거나 존재하지 않아도 예외로 중단하지 않습니다.
     * 후보 수집기가 빈 후보 목록을 반환하고, 선택기는 UNKNOWN 대표 라이선스 결과를 만듭니다.
     * 이렇게 해야 나중에 파이프라인에 연결했을 때 라이선스 분석 실패가 전체 분석을 불필요하게 막지 않습니다.
     *
     * @param runId 결과 JSON에 기록할 분석 실행 ID
     * @param repoRoot 분석 대상 저장소의 실제 루트 경로
     * @return 화면/API/Artifact 저장에 사용할 대표 라이선스 분석 JSON
     */
    public LicenseAnalysisJson analyze(String runId, Path repoRoot) {
        Path normalizedRepoRoot = normalizeRepoRoot(repoRoot);

        List<ProjectLicenseCandidate> candidates = candidateCollector.collect(normalizedRepoRoot);
        ProjectLicenseAnalysisResult selection = projectLicenseSelector.select(candidates);
        LicenseAnalysisJson output = jsonAssembler.assemble(runId, OffsetDateTime.now(), selection);

        log.info(
                "[LICENSE] representative license analysis completed. runId={}, repoRoot={}, candidates={}, selectedSpdxId={}, manualReviewRequired={}",
                runId,
                normalizedRepoRoot,
                candidates.size(),
                selectedSpdxId(output),
                manualReviewRequired(output)
        );

        return output;
    }

    /**
     * 입력받은 repoRoot를 비교와 로그에 안정적으로 남길 수 있는 절대 경로로 정규화합니다.
     * null은 그대로 유지해 후보 수집기가 UNKNOWN 흐름으로 안전하게 처리하도록 둡니다.
     */
    private Path normalizeRepoRoot(Path repoRoot) {
        if (repoRoot == null) {
            return null;
        }
        return repoRoot.toAbsolutePath().normalize();
    }

    /**
     * 로그에 남길 대표 SPDX ID를 안전하게 꺼냅니다.
     * JSON 조립 결과가 비정상적으로 비어 있어도 로그 출력 때문에 분석이 실패하지 않게 합니다.
     */
    private String selectedSpdxId(LicenseAnalysisJson output) {
        if (output == null || output.getProjectLicense() == null) {
            return "UNKNOWN";
        }
        return output.getProjectLicense().getSpdxId();
    }

    /**
     * 로그에 남길 수동 검토 필요 여부를 안전하게 꺼냅니다.
     * displayPolicy가 비어 있으면 보수적으로 true로 보고 확인이 필요한 결과로 기록합니다.
     */
    private Boolean manualReviewRequired(LicenseAnalysisJson output) {
        if (output == null || output.getDisplayPolicy() == null) {
            return true;
        }
        return output.getDisplayPolicy().getRequireManualReview();
    }
}
