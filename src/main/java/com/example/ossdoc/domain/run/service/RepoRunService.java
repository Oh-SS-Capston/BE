package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheFailedCooldownResult;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLockService;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLookupService;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.dto.response.RepoRunRecentResponse;
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
import com.example.ossdoc.domain.run.support.GithubClient;
import com.example.ossdoc.domain.run.support.GithubRepoRef;
import com.example.ossdoc.domain.run.support.GithubUrlParser;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeySeed;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoRunService {

    private static final List<PipelineJobStatus> ACTIVE_PIPELINE_JOB_STATUSES = List.of(
            PipelineJobStatus.QUEUED,
            PipelineJobStatus.RUNNING,
            PipelineJobStatus.RETRYING
    );
    private static final String CACHE_WAIT_STATUS_MESSAGE =
            "[CACHE_WAIT] ?숈씪 而ㅻ컠 FULL 遺꾩꽍 寃곌낵瑜?湲곕떎由щ뒗 以묒엯?덈떎.";

    private final RepoRunRepository repoRunRepository;
    private final UserRepository userRepository;
    private final GithubClient githubClient;
    private final WorkspaceManager workspaceManager;
    private final RunPipelineQueueService pipelineQueueService;
    private final RunAnalysisCacheKeyFactory runAnalysisCacheKeyFactory;
    private final AnalysisCacheLookupService analysisCacheLookupService;
    private final AnalysisCacheLockService analysisCacheLockService;
    private final AnalysisCacheProperties analysisCacheProperties;
    private final RunPipelineJobRepository runPipelineJobRepository;
    private final RunPipelineStepExecutionRepository runPipelineStepExecutionRepository;
    private final ArtifactRepository artifactRepository;

    @Transactional
    public RepoRunCreateResponse createRun(RepoRunCreateRequest req, Long userId) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));

        GithubRepoRef parsed = GithubUrlParser.parse(req.getRepoUrl(), req.getRef());

        log.info(
                "Create run requested userId={}, owner={}, repo={}, requestedRef={}",
                userId,
                parsed.getOwner(),
                parsed.getRepo(),
                req.getRef()
        );

        String ref = parsed.getRef();

        if (ref == null || ref.isBlank()) {
            ref = githubClient.resolveDefaultBranch(parsed.getOwner(), parsed.getRepo());

            log.info(
                    "Resolved default branch owner={}, repo={}, ref={}",
                    parsed.getOwner(),
                    parsed.getRepo(),
                    ref
            );
        }

        String commitSha = githubClient.resolveCommitSha(
                parsed.getOwner(),
                parsed.getRepo(),
                ref
        );

        RunAnalysisCacheKeySeed cacheKeySeed = buildCacheKeySeed(req.getRepoUrl(), commitSha);
        String analysisCacheKey = runAnalysisCacheKeyFactory.buildKey(cacheKeySeed);
        String normalizedRepoUrl = runAnalysisCacheKeyFactory.normalizeRepoUrlForCache(req.getRepoUrl());

        log.info(
                "Resolved commit SHA owner={}, repo={}, ref={}, sha={}",
                parsed.getOwner(),
                parsed.getRepo(),
                ref,
                abbreviateSha(commitSha)
        );
        log.info(
                "[CACHE] analysis key prepared. sha={}, key={}",
                abbreviateSha(commitSha),
                abbreviateCacheKey(analysisCacheKey)
        );
        boolean forceRebuild = req.isForceRebuild();
        if (forceRebuild) {
            log.info(
                    "[CACHE] forceRebuild requested. read bypass enabled. sha={}, key={}",
                    abbreviateSha(commitSha),
                    abbreviateCacheKey(analysisCacheKey)
            );
        }

        if (!forceRebuild) {
            AnalysisCacheLookupResult cacheLookupResult = analysisCacheLookupService.lookupReady(
                    analysisCacheKey,
                    normalizedRepoUrl,
                    commitSha
            );

        if (cacheLookupResult.hit()) {
            RepoRun ownedCachedRun = resolveOwnedCachedRun(cacheLookupResult.sourceRunId(), userId);
            if (ownedCachedRun != null) {
                if (isFullSuccessCacheSource(ownedCachedRun)) {
                    log.info(
                            "[CACHE] hit accepted. requestSha={}, sourceRunId={}, reason={}",
                            abbreviateSha(commitSha),
                            ownedCachedRun.getRunId(),
                            cacheLookupResult.reason()
                    );
                    return toCreateResponse(
                            ownedCachedRun,
                            true,
                            cacheLookupResult.cacheKey(),
                            cacheLookupResult.sourceRunId()
                    );
                }

                log.warn(
                        "[CACHE] hit rejected. run is not full-success. sourceRunId={}, runStatus={}",
                        ownedCachedRun.getRunId(),
                        ownedCachedRun.getStatus()
                );
            }

            RepoRun sourceRun = resolveAnyCachedRun(cacheLookupResult.sourceRunId());
            if (sourceRun != null) {
                if (isFullSuccessCacheSource(sourceRun)) {
                    RepoRun sharedCachedRun = createSharedCachedRun(sourceRun, owner, userId);
                    log.info(
                            "[CACHE] global hit accepted. sourceRunId={}, sharedRunId={}, requestUserId={}",
                            sourceRun.getRunId(),
                            sharedCachedRun.getRunId(),
                            userId
                    );
                    return toCreateResponse(
                            sharedCachedRun,
                            true,
                            cacheLookupResult.cacheKey(),
                            sourceRun.getRunId(),
                            sourceRun
                    );
                }

                log.warn(
                        "[CACHE] hit rejected. source run is not full-success. sourceRunId={}, runStatus={}",
                        sourceRun.getRunId(),
                        sourceRun.getStatus()
                );
            } else {
                log.warn(
                        "[CACHE] hit payload exists but source run is missing. sourceRunId={}, fallback=MISS",
                        cacheLookupResult.sourceRunId()
                );
            }
        } else {
            log.info(
                "[CACHE] miss. sha={}, reason={}",
                abbreviateSha(commitSha),
                cacheLookupResult.reason()
            );
        }

        /*
         * W10(?꾪솕):
         * - READY miss ?댄썑, 吏곸쟾 FAILED 罹먯떆??吏㏃? 荑⑤떎??湲곕낯 30珥? ?щ?瑜??뺤씤?⑸땲??
         * - ?숈씪 ?ъ슜?먯쓽 ?ㅽ뙣 run?대㈃ ?ъ궗?⑺빐 利됱떆 ?묐떟?⑸땲??
         * - ??ъ슜???ㅽ뙣 run? ???붿껌??留됱? ?딄퀬, ?꾨옒 ?좉퇋 遺꾩꽍 寃쎈줈濡?洹몃?濡?吏꾪뻾?⑸땲??
         */
        AnalysisCacheFailedCooldownResult failedCooldownResult =
                analysisCacheLookupService.lookupFailedCooldown(
                        analysisCacheKey,
                        normalizedRepoUrl,
                        commitSha
                );
        if (failedCooldownResult.coolingDown()) {
            log.info(
                    "[CACHE][FAILED] cooldown active. cacheKey={}, sourceRunId={}, retryAfter={}, reason={}",
                    abbreviateCacheKey(failedCooldownResult.cacheKey()),
                    failedCooldownResult.sourceRunId(),
                    failedCooldownResult.retryAfter(),
                    failedCooldownResult.reason()
            );

            RepoRun ownedFailedRun = resolveOwnedCachedRun(failedCooldownResult.sourceRunId(), userId);
            if (ownedFailedRun != null) {
                return toCreateResponse(
                        ownedFailedRun,
                        false,
                        failedCooldownResult.cacheKey(),
                        failedCooldownResult.sourceRunId()
                );
            }

            RepoRun sourceFailedRun = resolveAnyCachedRun(failedCooldownResult.sourceRunId());
            if (sourceFailedRun != null) {
                /*
                 * ??ъ슜???ㅽ뙣??怨듭쑀 蹂듭젣濡?留됱? ?딆뒿?덈떎.
                 * ?ъ슜??愿?먯뿉??"?⑥쓽 ?ㅽ뙣 ?뚮Ц??紐??꾨뒗" ?듬떟?⑥쓣 ?놁븷湲??꾪빐
                 * ??遺꾩꽍 run?쇰줈 ?ъ떆?꾪븷 ???덇쾶 ?꾨옒 enqueue 寃쎈줈濡??대갚?⑸땲??
                 */
                if (sourceFailedRun.getOwner() != null
                        && sourceFailedRun.getOwner().getId() != null
                        && sourceFailedRun.getOwner().getId().equals(userId)) {
                    return toCreateResponse(
                            sourceFailedRun,
                            false,
                            failedCooldownResult.cacheKey(),
                            sourceFailedRun.getRunId()
                    );
                }
                log.info(
                        "[CACHE][FAILED] cooldown source belongs to another user. fallback=NEW_ANALYSIS, sourceRunId={}, ownerId={}, requestUserId={}",
                        sourceFailedRun.getRunId(),
                        sourceFailedRun.getOwner() == null ? null : sourceFailedRun.getOwner().getId(),
                        userId
                );
            }

            log.warn(
                    "[CACHE][FAILED] cooldown source run missing. fallback=NEW_ANALYSIS, sourceRunId={}",
                    failedCooldownResult.sourceRunId()
            );
        }
        }

        /*
         * W09 ?뺤옣:
         * - ???띾뱷 ?깃났: 湲곗〈泥섎읆 利됱떆 ?좉퇋 遺꾩꽍 enqueue
         * - ??寃쏀빀:
         *   1) ???쒖꽦 run?대㈃ attach
         *   2) ??ъ슜???쒖꽦 run + buildMode=FULL(?먮뒗 誘명솗?? -> ???뚯쑀 WAIT run ?앹꽦 ??罹먯떆 ?湲?         *   3) ??ъ슜???쒖꽦 run + buildMode!=FULL -> 議곌린 ?덉텧(?낅┰ 遺꾩꽍 利됱떆 enqueue)
         */
        /*
         * ??owner token??runId 湲곕컲?쇰줈 怨좎젙???〓땲??
         * ?댁쑀:
         * - enqueue ?쒖젏???띾뱷???쎌쓣 worker 醫낅즺 ?쒖젏?먯꽌 媛숈? ?좏겙?쇰줈 ?덉쟾 ?댁젣?댁빞
         *   TTL 留뚮즺 ?꾩뿉???ㅼ쓬 ?숈씪 ?붿껌??利됱떆 罹먯떆 hit 寃쎈줈瑜??????덉뒿?덈떎.
         */
        String runId = generateRunId();
        String lockOwnerToken = buildLockOwnerToken(runId);
        boolean lockAcquired = analysisCacheLockService.tryAcquire(analysisCacheKey, lockOwnerToken);
        if (!lockAcquired) {
            if (forceRebuild) {
                /*
                 * W11:
                 * - forceRebuild??湲곗〈 ?ㅽ뻾 ?ъ궗?⑸낫??"?좉퇋 ?щ텇?? ?섎룄媛 ?곗꽑?낅땲??
                 * - ??寃쏀빀 ??attach/wait濡?遺숈? ?딄퀬, ?꾨옒 ?좉퇋 遺꾩꽍 enqueue 寃쎈줈濡??대갚?⑸땲??
                 */
                log.info(
                        "[CACHE][LOCK] contention ignored by forceRebuild. fallback=NEW_ANALYSIS, cacheKey={}",
                        abbreviateCacheKey(analysisCacheKey)
                );
            } else {
            RepoRun activeRun = resolveActiveRunForSameRepoAndSha(
                    parsed.getOwner(),
                    parsed.getRepo(),
                    commitSha
            );

            if (activeRun == null) {
                log.info(
                        "[CACHE][LOCK] contention but active run not found. fallback=EARLY_ESCAPE, cacheKey={}",
                        abbreviateCacheKey(analysisCacheKey)
                );
            } else if (activeRun.getOwner() != null && activeRun.getOwner().getId() != null
                    && activeRun.getOwner().getId().equals(userId)) {
                log.info(
                        "[CACHE][LOCK] contention resolved by attach. requestUserId={}, runId={}, status={}",
                        userId,
                        activeRun.getRunId(),
                        activeRun.getStatus()
                );
                return toCreateResponse(
                        activeRun,
                        false,
                        analysisCacheKey,
                        null
                );
            } else {
                BuildMode sourceBuildMode = resolveBuildMode(activeRun.getRunId());
                if (sourceBuildMode != null && sourceBuildMode != BuildMode.FULL) {
                    log.info(
                            "[CACHE][LOCK] early-escape triggered. sourceRunId={}, sourceBuildMode={}",
                            activeRun.getRunId(),
                            sourceBuildMode
                    );
                    // 議곌린 ?덉텧: ?꾨옒 ?좉퇋 遺꾩꽍 enqueue 寃쎈줈濡??대갚?⑸땲??
                } else {
                    RepoRun waitingRun = createCacheWaitingRun(req, owner, parsed, ref, commitSha, userId);
                    log.info(
                            "[CACHE][LOCK] waiting run created. requestUserId={}, runId={}, sourceRunId={}, sourceBuildMode={}",
                            userId,
                            waitingRun.getRunId(),
                            activeRun.getRunId(),
                            sourceBuildMode
                    );
                    return toCreateResponse(
                            waitingRun,
                            false,
                            analysisCacheKey,
                            activeRun.getRunId()
                    );
                }
            }
            }
        }

        Path wsRoot = workspaceManager.workspaceRoot(runId);

        log.info("Workspace prepared runId={}, workspaceRoot={}", runId, wsRoot);

        RepoRun run = new RepoRun(
                runId,
                owner,
                req.getRepoUrl(),
                parsed.getOwner(),
                parsed.getRepo(),
                ref,
                commitSha,
                wsRoot.toString()
        );

        repoRunRepository.save(run);

        try {
            pipelineQueueService.enqueue(run, userId);
        } catch (RuntimeException e) {
            /*
             * enqueue ?ㅽ뙣 ?쒖뿉???꾩옱 ?붿껌?????앹꽦???ㅽ뙣?덉쑝誘濡??쎌쓣 利됱떆 ?댁젣?⑸땲??
             * ?뺤긽 寃쎈줈?먯꽌??worker媛 吏꾪뻾?섎뒗 ?숈븞 TTL濡??먯뿰 留뚮즺?섎룄濡??좎??⑸땲??
             */
            analysisCacheLockService.releaseIfOwned(analysisCacheKey, lockOwnerToken);
            throw e;
        }

        log.info(
                "Run queued runId={}, status={}, sha={}",
                runId,
                run.getStatus(),
                abbreviateSha(commitSha)
        );

        return toCreateResponse(
                run,
                false,
                analysisCacheKey,
                null
        );
    }

    @Transactional(readOnly = true)
    public List<RepoRunRecentResponse> getRecentRuns(Long userId) {
        return repoRunRepository.findRecentRunsByOwner(userId)
                .stream()
                .map(RepoRunRecentResponse::from)
                .toList();
    }

    private String abbreviateSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "<empty>";
        }

        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    /**
     * 罹먯떆 ???쒕뱶 援ъ꽦 ?꾩슜 硫붿꽌?쒖엯?덈떎.
     * <p>
     * 遺꾨━???댁쑀:
     * - createRun 蹂몃Ц?먯꽌 踰꾩쟾/?듭뀡 議곕┰ 濡쒖쭅??遺꾨━??媛?낆꽦怨??좎?蹂댁닔?깆쓣 ?믪엯?덈떎.
     * - 異뷀썑 ?듭뀡 異뺤씠 ?섏뼱?섎룄 ??硫붿꽌?쒕쭔 ?섏젙?섎㈃ ?섎룄濡?蹂寃?吏?먯쓣 怨좎젙?⑸땲??
     */
    private RunAnalysisCacheKeySeed buildCacheKeySeed(String repoUrl, String commitSha) {
        return RunAnalysisCacheKeySeed.builder()
                .repoUrl(repoUrl)
                .commitSha(commitSha)
                .pipelineContractVersion(analysisCacheProperties.getPipelineContractVersion())
                .llmProfileVersion(analysisCacheProperties.getLlmProfileVersion())
                .promptTemplateVersion(analysisCacheProperties.getPromptTemplateVersion())
                .outputSchemaVersion(analysisCacheProperties.getOutputSchemaVersion())
                .runOptionsSignature(analysisCacheProperties.getDefaultRunOptionsSignature())
                .build();
    }

    /**
     * 罹먯떆 hit sourceRun???꾩옱 ?ъ슜???뚯쑀?몄? ?뺤씤?⑸땲??
     */
    private RepoRun resolveOwnedCachedRun(String sourceRunId, Long userId) {
        if (sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }
        return repoRunRepository.findOwnedRun(sourceRunId, userId)
                .orElse(null);
    }

    /**
     * ?꾩뿭 罹먯떆 ?섏슜???꾪빐 sourceRunId 議댁옱 ?щ?留??뺤씤?⑸땲??
     */
    private RepoRun resolveAnyCachedRun(String sourceRunId) {
        if (sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }
        return repoRunRepository.findById(sourceRunId)
                .orElse(null);
    }

    /**
     * 罹먯떆 ?ъ궗???덉슜 議곌굔(? ?깃났)??寃?ы빀?덈떎.
     *
     * ?덉슜 議곌굔:
     * 1) repo_run.status == SUCCESS
     * 2) run_pipeline_job.status == SUCCESS
     *
     * ??議곌굔??????留뚯”?섏? ?딆쑝硫?cache hit瑜??섏슜?섏? ?딄퀬 ?좉퇋 遺꾩꽍 寃쎈줈濡??대갚?⑸땲??
     */
    private boolean isFullSuccessCacheSource(RepoRun run) {
        if (run.getStatus() != RunStatus.SUCCESS) {
            return false;
        }

        return runPipelineJobRepository.findJobByRunId(run.getRunId())
                .map(job -> job.getStatus() == PipelineJobStatus.SUCCESS)
                .orElse(false);
    }

    /**
     * ?먮낯 run 寃곌낵瑜??붿껌???뚯쑀 run?쇰줈 蹂듭젣?⑸땲??
     *
     * ?대젃寃?援ы쁽???댁쑀:
     * - ?⑥닚???먮낯 runId瑜?諛섑솚?섎㈃ owner 寃利?progress/artifact)?먯꽌 李⑤떒?⑸땲??
     * - ?붿껌???뚯쑀 run?쇰줈 硫뷀?瑜?蹂듭젣?섎㈃ 湲곗〈 沅뚰븳 紐⑤뜽???좎???梨??꾩뿭 罹먯떆瑜??ъ궗?⑺븷 ???덉뒿?덈떎.
     */
    private RepoRun createSharedCachedRun(RepoRun sourceRun, User owner, Long requestUserId) {
        String sharedRunId = generateRunId();

        RepoRun sharedRun = new RepoRun(
                sharedRunId,
                owner,
                sourceRun.getRepoUrl(),
                sourceRun.getRepoOwner(),
                sourceRun.getRepoName(),
                sourceRun.getResolvedRef(),
                sourceRun.getCommitSha(),
                sourceRun.getWorkspaceRoot()
        );
        repoRunRepository.save(sharedRun);

        RunPipelineJob sharedJob = copyJobState(sourceRun.getRunId(), sharedRun, requestUserId);
        runPipelineJobRepository.save(sharedJob);

        copyStepExecutionSnapshots(sourceRun.getRunId(), sharedRun, sharedJob);
        copyArtifactsForSharedRun(sourceRun.getRunId(), sharedRun);

        return sharedRun;
    }

    /**
     * ?먮낯 run??理쒖쥌 ???곹깭瑜?蹂듭젣?⑸땲??
     * READY 罹먯떆???깃났 寃곌낵瑜??꾩젣濡??섎?濡?湲곕낯媛믪? SUCCESS?낅땲??
     */
    private RunPipelineJob copyJobState(String sourceRunId, RepoRun sharedRun, Long requestUserId) {
        RunPipelineJob sharedJob = RunPipelineJob.create(sharedRun, requestUserId);
        sharedJob.markSuccess();

        RunPipelineJob sourceJob = runPipelineJobRepository.findJobByRunId(sourceRunId)
                .orElse(null);

        if (sourceJob == null) {
            return sharedJob;
        }

        PipelineJobStatus sourceStatus = sourceJob.getStatus();
        if (sourceStatus == PipelineJobStatus.PARTIAL_SUCCESS) {
            sharedJob.markPartialSuccess(sourceJob.getFailureMessage());
        } else if (sourceStatus == PipelineJobStatus.FAILED) {
            sharedJob.markFailed(sourceJob.getFailureMessage(), sourceJob.getLastError());
        } else {
            sharedJob.markSuccess();
        }

        return sharedJob;
    }

    /**
     * ?먮낯 run???④퀎蹂??곹깭瑜?蹂듭젣??吏꾪뻾 ?곸꽭 ?붾㈃?먯꽌 ?숈씪???④퀎瑜??뺤씤?????덇쾶 ?⑸땲??
     */
    private void copyStepExecutionSnapshots(String sourceRunId, RepoRun sharedRun, RunPipelineJob sharedJob) {
        List<RunPipelineStepExecution> sourceSteps =
                runPipelineStepExecutionRepository.findStepsByRunId(sourceRunId);

        for (RunPipelineStepExecution sourceStep : sourceSteps) {
            RunPipelineStepExecution copied = RunPipelineStepExecution.create(
                    sharedJob,
                    sharedRun,
                    sourceStep.getStage()
            );

            PipelineStepStatus sourceStatus = sourceStep.getStatus();
            if (sourceStatus == PipelineStepStatus.SUCCESS) {
                copied.succeed(sourceStep.getMessage());
            } else if (sourceStatus == PipelineStepStatus.FAILED) {
                copied.fail(sourceStep.getErrorMessage());
            } else if (sourceStatus == PipelineStepStatus.SKIPPED) {
                copied.skip(sourceStep.getMessage());
            } else if (sourceStatus == PipelineStepStatus.RUNNING) {
                copied.succeed(sourceStep.getMessage());
            } else {
                copied.skip("罹먯떆 ?ъ궗?⑹쑝濡????湲??④퀎瑜??앸왂?덉뒿?덈떎.");
            }

            runPipelineStepExecutionRepository.save(copied);
        }
    }

    /**
     * ?먮낯 run???곗텧臾?硫뷀?瑜??붿껌???뚯쑀 run?쇰줈 蹂듭젣?⑸땲??
     * S3 ?ъ뾽濡쒕뱶 ?놁씠 DB ?덉퐫?쒕쭔 蹂듭젣?섎?濡?罹먯떆 hit 吏?곗씠 留ㅼ슦 ??뒿?덈떎.
     */
    private void copyArtifactsForSharedRun(String sourceRunId, RepoRun sharedRun) {
        List<Artifact> sourceArtifacts = artifactRepository.findAllByRun_RunIdOrderByArtifactIdAsc(sourceRunId);

        for (Artifact source : sourceArtifacts) {
            artifactRepository.save(new Artifact(
                    null,
                    sharedRun,
                    source.getKind(),
                    source.getSchemaVersion(),
                    source.getContentType(),
                    source.getPath(),
                    source.getMeta()
            ));
        }
    }

    /**
     * Run ?앹꽦 ?묐떟???쒖? ?щ㎎?쇰줈 議곕┰?⑸땲??
     *
     * ??遺꾨━?덈뒗媛:
     * - W07 ?붽뎄?ы빆(罹먯떆 硫뷀? ?묐떟)????吏?먯뿉???쇨??섍쾶 梨꾩슦湲??꾪븿?낅땲??
     * - hit/miss/怨듭쑀蹂듭젣 寃쎈줈媛 ?щ씪???묐떟 怨꾩빟???붾뱾由ъ? ?딄쾶 ?⑸땲??
     */
    private RepoRunCreateResponse toCreateResponse(
            RepoRun run,
            boolean cacheHit,
            String cacheKey,
            String sourceRunId
    ) {
        return toCreateResponse(run, cacheHit, cacheKey, sourceRunId, null);
    }

    private RepoRunCreateResponse toCreateResponse(
            RepoRun run,
            boolean cacheHit,
            String cacheKey,
            String sourceRunId,
            RepoRun analyzedSourceRun
    ) {
        RepoRun analyzedAtBaseRun = analyzedSourceRun != null ? analyzedSourceRun : run;

        return RepoRunCreateResponse.builder()
                .runId(run.getRunId())
                .status(run.getStatus())
                .commitSha(run.getCommitSha())
                .workspaceRoot(run.getWorkspaceRoot())
                .cacheHit(cacheHit)
                .cacheKey(cacheKey)
                .sourceRunId(sourceRunId)
                .createdAt(analyzedAtBaseRun.getCreatedAt())
                .updatedAt(analyzedAtBaseRun.getUpdatedAt())
                .build();
    }

    private String abbreviateCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return "<empty>";
        }
        return cacheKey.length() <= 12 ? cacheKey : cacheKey.substring(0, 12);
    }

    /**
     * ??ъ슜??FULL 遺꾩꽍??湲곕떎由ш린 ?꾪븳 ?붿껌???뚯쑀 run/job/step???앹꽦?⑸땲??
     *
     * 援ы쁽 ?댁쑀:
     * - 409 ?ъ떆??UX ???"??runId"瑜?利됱떆 諛섑솚??polling ?먮쫫???좎??⑸땲??
     * - worker媛 怨㏓컮濡??ㅽ뻾?섏? ?딅룄濡?job? RUNNING+臾대씫(lock null) ?湲??곹깭濡??〓땲??
     */
    private RepoRun createCacheWaitingRun(
            RepoRunCreateRequest req,
            User owner,
            GithubRepoRef parsed,
            String ref,
            String commitSha,
            Long userId
    ) {
        String waitingRunId = generateRunId();
        Path waitingWsRoot = workspaceManager.workspaceRoot(waitingRunId);

        RepoRun waitingRun = new RepoRun(
                waitingRunId,
                owner,
                req.getRepoUrl(),
                parsed.getOwner(),
                parsed.getRepo(),
                ref,
                commitSha,
                waitingWsRoot.toString()
        );
        repoRunRepository.save(waitingRun);

        RunPipelineJob waitingJob = RunPipelineJob.create(waitingRun, userId);
        waitingJob.markCacheWaiting(CACHE_WAIT_STATUS_MESSAGE);
        runPipelineJobRepository.save(waitingJob);

        RunPipelineStepExecution waitingStep = RunPipelineStepExecution.create(
                waitingJob,
                waitingRun,
                RunStage.QUEUED
        );
        waitingStep.start(CACHE_WAIT_STATUS_MESSAGE);
        runPipelineStepExecutionRepository.save(waitingStep);

        return waitingRun;
    }

    /**
     * W09 ??寃쏀빀 ?? ?숈씪 repo/sha??吏꾪뻾以?job??李얠븘 attach 媛???щ?瑜??먮떒?⑸땲??
     */
    private RepoRun resolveActiveRunForSameRepoAndSha(String repoOwner, String repoName, String commitSha) {
        return runPipelineJobRepository
                .findActiveJobsByRepoAndSha(
                        repoOwner,
                        repoName,
                        commitSha,
                        ACTIVE_PIPELINE_JOB_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(RunPipelineJob::getRun)
                .orElse(null);
    }

    /**
     * build_manifest meta??buildMode瑜??쎌뼱 FULL ?щ?瑜??먮떒?⑸땲??
     */
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

    /**
     * Redis lock value(owner token) ?앹꽦 洹쒖튃?낅땲??
     * owner 寃利??댁젣 ?쒖뿉 "?꾧? ?띾뱷???쎌씤吏"瑜??앸퀎?섍린 ?꾪빐 ?ъ슜?⑸땲??
     */
    private String buildLockOwnerToken(String runId) {
        return AnalysisCacheLockService.ownerTokenForRun(runId);
    }

    private String generateRunId() {
        return "run_"
                + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
                + "_"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}

