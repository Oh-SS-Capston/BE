package com.example.ossdoc.domain.run.cache.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.enums.PipelineJobStatus;
import com.example.ossdoc.domain.run.enums.PipelineStepStatus;
import com.example.ossdoc.domain.run.enums.RunStage;
import com.example.ossdoc.domain.run.enums.RunStatus;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineStepExecutionRepository;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeySeed;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CACHE_WAIT 상태 run을 주기적으로 해소하는 서비스입니다.
 *
 * 해소 정책:
 * 1) READY 캐시가 생기면 source 결과를 waiting run으로 복제 후 성공 처리
 * 2) source buildMode가 FULL이 아니면 즉시 조기 탈출(독립 분석 1회)
 * 3) source가 FULL 이후 실패/미발행이면 완화 모드로 독립 분석 1회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunCacheWaitResolveService {

    public static final String CACHE_WAIT_STATUS_PREFIX = "[CACHE_WAIT]";

    private static final List<PipelineJobStatus> ACTIVE_SOURCE_STATUSES = List.of(
            PipelineJobStatus.QUEUED,
            PipelineJobStatus.RUNNING,
            PipelineJobStatus.RETRYING
    );

    private static final List<PipelineJobStatus> FINISHED_SOURCE_STATUSES = List.of(
            PipelineJobStatus.SUCCESS,
            PipelineJobStatus.PARTIAL_SUCCESS,
            PipelineJobStatus.FAILED
    );

    private final RunPipelineJobRepository runPipelineJobRepository;
    private final RepoRunRepository repoRunRepository;
    private final RunPipelineStepExecutionRepository runPipelineStepExecutionRepository;
    private final ArtifactRepository artifactRepository;
    private final AnalysisCacheLookupService analysisCacheLookupService;
    private final RunAnalysisCacheKeyFactory runAnalysisCacheKeyFactory;
    private final AnalysisCacheProperties analysisCacheProperties;

    @Transactional
    public void reconcileWaitingRuns(int maxBatch) {
        if (maxBatch <= 0) {
            return;
        }

        List<RunPipelineJob> waitingJobs =
                runPipelineJobRepository.findCacheWaitingJobs(
                        PipelineJobStatus.RUNNING,
                        RunStage.QUEUED,
                        CACHE_WAIT_STATUS_PREFIX,
                        PageRequest.of(0, 20)
                );

        int limit = Math.min(waitingJobs.size(), maxBatch);
        for (int i = 0; i < limit; i++) {
            handleWaitingJob(waitingJobs.get(i));
        }
    }

    private void handleWaitingJob(RunPipelineJob waitingJob) {
        RepoRun waitingRun = waitingJob.getRun();
        String waitingRunId = waitingRun.getRunId();

        String cacheKey = buildCacheKey(waitingRun);
        String normalizedRepoUrl = runAnalysisCacheKeyFactory.normalizeRepoUrlForCache(waitingRun.getRepoUrl());

        AnalysisCacheLookupResult readyResult = analysisCacheLookupService.lookupReady(
                cacheKey,
                normalizedRepoUrl,
                waitingRun.getCommitSha()
        );

        if (readyResult.hit()) {
            RepoRun sourceRun = readyResult.sourceRunId() == null
                    ? null
                    : repoRunRepository.findById(readyResult.sourceRunId()).orElse(null);

            if (sourceRun != null && isFullSuccessSource(sourceRun)) {
                applyReadyCache(waitingJob, waitingRun, sourceRun);
                return;
            }
        }

        RunPipelineJob activeSourceJob = runPipelineJobRepository
                .findActiveJobsByRepoAndShaExcludingRun(
                        waitingRun.getRepoOwner(),
                        waitingRun.getRepoName(),
                        waitingRun.getCommitSha(),
                        waitingRunId,
                        ACTIVE_SOURCE_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .orElse(null);

        if (activeSourceJob != null) {
            BuildMode sourceBuildMode = resolveBuildMode(activeSourceJob.getRun().getRunId());
            if (sourceBuildMode != null && sourceBuildMode != BuildMode.FULL) {
                scheduleIndependentAnalysis(waitingJob, "원본 buildMode가 " + sourceBuildMode + "라 즉시 독립 분석으로 전환합니다.");
            }
            return;
        }

        RunPipelineJob latestFinishedSource = runPipelineJobRepository
                .findFinishedJobsByRepoAndShaExcludingRun(
                        waitingRun.getRepoOwner(),
                        waitingRun.getRepoName(),
                        waitingRun.getCommitSha(),
                        waitingRunId,
                        FINISHED_SOURCE_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .orElse(null);

        if (latestFinishedSource == null) {
            scheduleIndependentAnalysis(waitingJob, "원본 분석 상태를 찾을 수 없어 독립 분석으로 전환합니다.");
            return;
        }

        BuildMode latestBuildMode = resolveBuildMode(latestFinishedSource.getRun().getRunId());
        if (latestBuildMode == BuildMode.FULL) {
            scheduleIndependentAnalysis(waitingJob, "원본 FULL 분석이 READY 미발행/실패하여 완화 모드 1회 재분석을 수행합니다.");
            return;
        }

        scheduleIndependentAnalysis(
                waitingJob,
                "원본 buildMode가 " + (latestBuildMode == null ? "UNKNOWN" : latestBuildMode) + "라 독립 분석으로 전환합니다."
        );
    }

    /**
     * READY 캐시를 waiting run으로 이관합니다.
     * - job/run은 SUCCESS로 종료
     * - 단계 스냅샷/산출물은 source 기준으로 복제
     */
    private void applyReadyCache(RunPipelineJob waitingJob, RepoRun waitingRun, RepoRun sourceRun) {
        waitingJob.markSuccess();
        copyStepExecutionSnapshots(sourceRun.getRunId(), waitingRun, waitingJob);
        copyArtifacts(sourceRun.getRunId(), waitingRun);

        log.info(
                "[CACHE][WAIT] resolved by ready cache. waitingRunId={}, sourceRunId={}",
                waitingRun.getRunId(),
                sourceRun.getRunId()
        );
    }

    /**
     * 캐시 대기 run을 독립 분석 큐로 1회 재진입시킵니다.
     */
    private void scheduleIndependentAnalysis(RunPipelineJob waitingJob, String reason) {
        waitingJob.scheduleRetryFromCacheWait(reason);

        RunPipelineStepExecution queuedStep = findOrCreateQueuedStep(waitingJob);
        queuedStep.skip(reason);

        log.info(
                "[CACHE][WAIT] switched to independent analysis. runId={}, reason={}",
                waitingJob.getRun().getRunId(),
                reason
        );
    }

    private RunPipelineStepExecution findOrCreateQueuedStep(RunPipelineJob job) {
        return runPipelineStepExecutionRepository.findByJobAndStage(job, RunStage.QUEUED)
                .orElseGet(() -> runPipelineStepExecutionRepository.save(
                        RunPipelineStepExecution.create(job, job.getRun(), RunStage.QUEUED)
                ));
    }

    private boolean isFullSuccessSource(RepoRun run) {
        if (run.getStatus() != RunStatus.SUCCESS) {
            return false;
        }
        return runPipelineJobRepository.findByRun_RunId(run.getRunId())
                .map(job -> job.getStatus() == PipelineJobStatus.SUCCESS)
                .orElse(false);
    }

    private void copyStepExecutionSnapshots(String sourceRunId, RepoRun targetRun, RunPipelineJob targetJob) {
        List<RunPipelineStepExecution> sourceSteps =
                runPipelineStepExecutionRepository.findAllByRun_RunIdOrderByStepIdAsc(sourceRunId);
        List<RunPipelineStepExecution> targetSteps =
                runPipelineStepExecutionRepository.findAllByRun_RunIdOrderByStepIdAsc(targetRun.getRunId());

        Map<RunStage, RunPipelineStepExecution> targetByStage = new EnumMap<>(RunStage.class);
        for (RunPipelineStepExecution step : targetSteps) {
            targetByStage.put(step.getStage(), step);
        }

        for (RunPipelineStepExecution sourceStep : sourceSteps) {
            RunPipelineStepExecution targetStep = targetByStage.get(sourceStep.getStage());
            if (targetStep == null) {
                targetStep = RunPipelineStepExecution.create(targetJob, targetRun, sourceStep.getStage());
            }

            applyStepStatus(targetStep, sourceStep);
            runPipelineStepExecutionRepository.save(targetStep);
        }
    }

    private void applyStepStatus(RunPipelineStepExecution targetStep, RunPipelineStepExecution sourceStep) {
        PipelineStepStatus sourceStatus = sourceStep.getStatus();
        if (sourceStatus == PipelineStepStatus.SUCCESS) {
            targetStep.succeed(sourceStep.getMessage());
        } else if (sourceStatus == PipelineStepStatus.FAILED) {
            targetStep.fail(sourceStep.getErrorMessage());
        } else if (sourceStatus == PipelineStepStatus.SKIPPED) {
            targetStep.skip(sourceStep.getMessage());
        } else if (sourceStatus == PipelineStepStatus.RUNNING) {
            targetStep.succeed(sourceStep.getMessage());
        } else {
            targetStep.skip("캐시 재사용으로 큐 대기 단계를 생략했습니다.");
        }
    }

    private void copyArtifacts(String sourceRunId, RepoRun targetRun) {
        List<Artifact> sourceArtifacts = artifactRepository.findAllByRun_RunIdOrderByArtifactIdAsc(sourceRunId);
        for (Artifact source : sourceArtifacts) {
            artifactRepository.save(new Artifact(
                    null,
                    targetRun,
                    source.getKind(),
                    source.getSchemaVersion(),
                    source.getContentType(),
                    source.getPath(),
                    source.getMeta()
            ));
        }
    }

    private String buildCacheKey(RepoRun run) {
        RunAnalysisCacheKeySeed seed = RunAnalysisCacheKeySeed.builder()
                .repoUrl(run.getRepoUrl())
                .commitSha(run.getCommitSha())
                .pipelineContractVersion(analysisCacheProperties.getPipelineContractVersion())
                .llmProfileVersion(analysisCacheProperties.getLlmProfileVersion())
                .promptTemplateVersion(analysisCacheProperties.getPromptTemplateVersion())
                .outputSchemaVersion(analysisCacheProperties.getOutputSchemaVersion())
                .runOptionsSignature(analysisCacheProperties.getDefaultRunOptionsSignature())
                .build();
        return runAnalysisCacheKeyFactory.buildKey(seed);
    }

    private BuildMode resolveBuildMode(String runId) {
        return artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST)
                .map(Artifact::getMeta)
                .map(meta -> meta.path("buildMode").asText(null))
                .map(this::toBuildMode)
                .orElse(null);
    }

    private BuildMode toBuildMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BuildMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
