package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * 대표 라이선스 분석을 전체 분석 파이프라인에 연결하는 얇은 어댑터 서비스입니다.
 *
 * <p>역할:
 * - runId로 RepoRun을 조회합니다.
 * - RepoRun.workspaceRoot 아래의 repo 디렉터리를 찾습니다.
 * - 기존 RepoRootResolver를 통해 실제 분석 루트가 한 단계 안쪽인지 보정합니다.
 * - LicenseAnalysisService로 대표 라이선스를 분석합니다.
 * - LicenseAnalysisArtifactPublisher로 analysis/license_analysis.json 산출물을 저장합니다.
 *
 * <p>파이프라인 위치:
 * 이 서비스는 RunPipelineExecutor에서 SNAPSHOT 직후, BUILD 이전에 호출됩니다.
 * 라이선스 분석은 빌드 산출물이 아니라 저장소 루트 파일만 보기 때문에 이 위치가 가장 독립적입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseAnalysisPipelineService {

    private final RepoRunRepository repoRunRepository;
    private final RepoRootResolver repoRootResolver;
    private final LicenseAnalysisService licenseAnalysisService;
    private final LicenseAnalysisArtifactPublisher artifactPublisher;

    /**
     * runId 기준으로 대표 라이선스를 분석하고 license_analysis.json Artifact를 발행합니다.
     *
     * <p>주의:
     * 이 메서드는 파이프라인의 선택 단계에서 호출됩니다.
     * 따라서 호출부인 RunPipelineExecutor가 실패를 잡아 PARTIAL_SUCCESS로 처리할 수 있게 예외를 숨기지 않습니다.
     *
     * @param runId 분석 실행 ID
     * @return 저장된 license_analysis.json Artifact
     */
    @Transactional
    public Artifact analyzeAndPublish(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        Path repoRoot = resolveAnalysisRoot(run);
        LicenseAnalysisJson output = licenseAnalysisService.analyze(run.getRunId(), repoRoot);
        Artifact artifact = artifactPublisher.publish(run, output);

        log.info(
                "[LICENSE] pipeline license step completed. runId={}, repoRoot={}, selectedSpdxId={}, artifactId={}",
                run.getRunId(),
                repoRoot,
                selectedSpdxId(output),
                artifact.getArtifactId()
        );

        return artifact;
    }

    /**
     * 라이선스 분석에 사용할 실제 저장소 루트를 계산합니다.
     *
     * <p>계산 규칙:
     * - RepoRun.workspaceRoot가 있으면 {workspaceRoot}/repo를 기본 루트로 봅니다.
     * - clone/zip 해제 구조에 따라 코드가 한 단계 하위 폴더에 들어간 경우가 있으므로 RepoRootResolver로 보정합니다.
     * - workspaceRoot가 비어 있으면 null을 반환합니다. 이 경우 LicenseAnalysisService가 UNKNOWN 결과를 만들 수 있습니다.
     */
    private Path resolveAnalysisRoot(RepoRun run) {
        if (run.getWorkspaceRoot() == null || run.getWorkspaceRoot().isBlank()) {
            log.warn("[LICENSE] workspaceRoot is empty. UNKNOWN license output will be generated. runId={}", run.getRunId());
            return null;
        }

        Path workspaceRepoRoot = Path.of(run.getWorkspaceRoot())
                .resolve("repo")
                .toAbsolutePath()
                .normalize();

        Path actualRepoRoot = repoRootResolver.resolveActualRoot(workspaceRepoRoot)
                .toAbsolutePath()
                .normalize();

        log.info(
                "[LICENSE] repository root resolved. runId={}, workspaceRepoRoot={}, actualRepoRoot={}",
                run.getRunId(),
                workspaceRepoRoot,
                actualRepoRoot
        );

        return actualRepoRoot;
    }

    /**
     * 로그에 남길 대표 SPDX ID를 안전하게 꺼냅니다.
     */
    private String selectedSpdxId(LicenseAnalysisJson output) {
        if (output == null || output.getProjectLicense() == null) {
            return "UNKNOWN";
        }
        return output.getProjectLicense().getSpdxId();
    }
}
