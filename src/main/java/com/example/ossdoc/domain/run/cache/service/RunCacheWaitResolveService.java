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
 * CACHE_WAIT ?곹깭 run??二쇨린?곸쑝濡??댁냼?섎뒗 ?쒕퉬?ㅼ엯?덈떎.
 *
 * ?댁냼 ?뺤콉:
 * 1) READY 罹먯떆媛 ?앷린硫?source 寃곌낵瑜?waiting run?쇰줈 蹂듭젣 ???깃났 泥섎━
 * 2) source buildMode媛 FULL???꾨땲硫?利됱떆 議곌린 ?덉텧(?낅┰ 遺꾩꽍 1??
 * 3) source媛 FULL ?댄썑 ?ㅽ뙣/誘몃컻?됱씠硫??꾪솕 紐⑤뱶濡??낅┰ 遺꾩꽍 1?? */
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
                waitingRun.getCommitSha(),
                waitingRun.getLlmProvider() == null ? null : waitingRun.getLlmProvider().name()
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
                scheduleIndependentAnalysis(waitingJob, "?먮낯 buildMode媛 " + sourceBuildMode + "??利됱떆 ?낅┰ 遺꾩꽍?쇰줈 ?꾪솚?⑸땲??");
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
            scheduleIndependentAnalysis(waitingJob, "?먮낯 遺꾩꽍 ?곹깭瑜?李얠쓣 ???놁뼱 ?낅┰ 遺꾩꽍?쇰줈 ?꾪솚?⑸땲??");
            return;
        }

        BuildMode latestBuildMode = resolveBuildMode(latestFinishedSource.getRun().getRunId());
        if (latestBuildMode == BuildMode.FULL) {
            scheduleIndependentAnalysis(waitingJob, "?먮낯 FULL 遺꾩꽍??READY 誘몃컻???ㅽ뙣?섏뿬 ?꾪솕 紐⑤뱶 1???щ텇?앹쓣 ?섑뻾?⑸땲??");
            return;
        }

        scheduleIndependentAnalysis(
                waitingJob,
                "?먮낯 buildMode媛 " + (latestBuildMode == null ? "UNKNOWN" : latestBuildMode) + "???낅┰ 遺꾩꽍?쇰줈 ?꾪솚?⑸땲??"
        );
    }

    /**
     * READY 罹먯떆瑜?waiting run?쇰줈 ?닿??⑸땲??
     * - job/run? SUCCESS濡?醫낅즺
     * - ?④퀎 ?ㅻ깄???곗텧臾쇱? source 湲곗??쇰줈 蹂듭젣
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
     * 罹먯떆 ?湲?run???낅┰ 遺꾩꽍 ?먮줈 1???ъ쭊?낆떆?듬땲??
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
        return runPipelineStepExecutionRepository.findStepByJobAndStage(job, RunStage.QUEUED)
                .orElseGet(() -> runPipelineStepExecutionRepository.save(
                        RunPipelineStepExecution.create(job, job.getRun(), RunStage.QUEUED)
                ));
    }

    private boolean isFullSuccessSource(RepoRun run) {
        if (run.getStatus() != RunStatus.SUCCESS) {
            return false;
        }
        return runPipelineJobRepository.findJobByRunId(run.getRunId())
                .map(job -> job.getStatus() == PipelineJobStatus.SUCCESS)
                .orElse(false);
    }

    private void copyStepExecutionSnapshots(String sourceRunId, RepoRun targetRun, RunPipelineJob targetJob) {
        List<RunPipelineStepExecution> sourceSteps =
                runPipelineStepExecutionRepository.findStepsByRunId(sourceRunId);
        List<RunPipelineStepExecution> targetSteps =
                runPipelineStepExecutionRepository.findStepsByRunId(targetRun.getRunId());

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
            targetStep.skip("罹먯떆 ?ъ궗?⑹쑝濡????湲??④퀎瑜??앸왂?덉뒿?덈떎.");
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
                .llmProvider(run.getLlmProvider() == null ? null : run.getLlmProvider().name())
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

