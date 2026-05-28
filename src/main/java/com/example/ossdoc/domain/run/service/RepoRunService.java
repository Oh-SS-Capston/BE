package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.membership.enums.AnalysisAccessType;
import com.example.ossdoc.domain.membership.service.MembershipAccessService;
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
            "[CACHE_WAIT] 동일 커밋 FULL 분석 결과를 기다리는 중입니다.";

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
    private final MembershipAccessService membershipAccessService;

    @Transactional
    public RepoRunCreateResponse createRun(RepoRunCreateRequest req, Long userId) {
        User owner = userRepository.findById(userId)
                .filter(User::isActive)
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

                        /*
                         * 내 기존 성공 run을 그대로 반환하는 경우입니다.
                         * 새 분석 결과를 만드는 것이 아니므로 무료 분석권을 차감하지 않습니다.
                         */
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
                        /*
                         * 타사용자의 성공 캐시 결과를 내 소유 run으로 복제하는 경우입니다.
                         * 사용자는 새 결과 접근권을 얻는 것이므로 무료 분석권 또는 멤버십 권한을 확인합니다.
                         */
                        AnalysisAccessType accessType = membershipAccessService.grantAnalysisStart(owner);

                        RepoRun sharedCachedRun = createSharedCachedRun(
                                sourceRun,
                                owner,
                                userId,
                                accessType
                        );

                        log.info(
                                "[CACHE] global hit accepted. sourceRunId={}, sharedRunId={}, requestUserId={}, accessType={}",
                                sourceRun.getRunId(),
                                sharedCachedRun.getRunId(),
                                userId,
                                accessType
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
             * W10(완화):
             * - READY miss 이후, 직전 FAILED 캐시의 짧은 쿨다운(기본 30초) 여부를 확인합니다.
             * - 동일 사용자의 실패 run이면 재사용해 즉시 응답합니다.
             * - 타사용자 실패 run은 내 요청을 막지 않고, 아래 신규 분석 경로로 그대로 진행합니다.
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
                    /*
                     * 내 실패 run을 재사용하는 경우입니다.
                     * 새 분석을 만드는 것이 아니므로 무료 분석권을 차감하지 않습니다.
                     */
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
                     * 타사용자 실패는 공유 복제로 막지 않습니다.
                     * 사용자 관점에서 "남의 실패 때문에 못 도는" 답답함을 없애기 위해
                     * 내 분석 run으로 재시도할 수 있게 아래 enqueue 경로로 폴백합니다.
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
         * W09 확장:
         * - 락 획득 성공: 기존처럼 즉시 신규 분석 enqueue
         * - 락 경합:
         *   1) 내 활성 run이면 attach
         *   2) 타사용자 활성 run + buildMode=FULL(또는 미확정) -> 내 소유 WAIT run 생성 후 캐시 대기
         *   3) 타사용자 활성 run + buildMode!=FULL -> 조기 탈출(독립 분석 즉시 enqueue)
         */

        /*
         * 락 owner token을 runId 기반으로 고정해 둡니다.
         * 이유:
         * - enqueue 시점에 획득한 락을 worker 종료 시점에서 같은 토큰으로 안전 해제해야
         *   TTL 만료 전에도 다음 동일 요청이 즉시 캐시 hit 경로를 탈 수 있습니다.
         */
        String runId = generateRunId();
        String lockOwnerToken = buildLockOwnerToken(runId);
        boolean lockAcquired = analysisCacheLockService.tryAcquire(analysisCacheKey, lockOwnerToken);

        if (!lockAcquired) {
            if (forceRebuild) {
                /*
                 * W11:
                 * - forceRebuild는 기존 실행 재사용보다 "신규 재분석" 의도가 우선입니다.
                 * - 락 경합 시 attach/wait로 붙지 않고, 아래 신규 분석 enqueue 경로로 폴백합니다.
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
                } else if (activeRun.getOwner() != null
                        && activeRun.getOwner().getId() != null
                        && activeRun.getOwner().getId().equals(userId)) {
                    log.info(
                            "[CACHE][LOCK] contention resolved by attach. requestUserId={}, runId={}, status={}",
                            userId,
                            activeRun.getRunId(),
                            activeRun.getStatus()
                    );

                    /*
                     * 내 활성 run에 attach하는 경우입니다.
                     * 이미 내 run이 있으므로 무료 분석권을 차감하지 않습니다.
                     */
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
                        /*
                         * 조기 탈출:
                         * 아래 신규 분석 enqueue 경로로 폴백합니다.
                         * 신규 run을 만들 때 무료 분석권 또는 멤버십 권한을 확인합니다.
                         */
                    } else {
                        /*
                         * 타사용자의 FULL 분석을 기다리는 내 WAIT run을 생성하는 경우입니다.
                         * 내 소유 run이 새로 만들어지므로 무료 분석권 또는 멤버십 권한을 확인합니다.
                         */
                        AnalysisAccessType accessType = membershipAccessService.grantAnalysisStart(owner);

                        RepoRun waitingRun = createCacheWaitingRun(
                                req,
                                owner,
                                parsed,
                                ref,
                                commitSha,
                                userId,
                                accessType
                        );

                        log.info(
                                "[CACHE][LOCK] waiting run created. requestUserId={}, runId={}, sourceRunId={}, sourceBuildMode={}, accessType={}",
                                userId,
                                waitingRun.getRunId(),
                                activeRun.getRunId(),
                                sourceBuildMode,
                                accessType
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

        /*
         * 신규 분석 run을 생성하는 경우입니다.
         * GitHub URL 파싱, 기본 브랜치 조회, commitSha resolve, cache attach/wait 판단이 끝난 뒤에
         * 무료 분석권 또는 멤버십 권한을 확인합니다.
         *
         * 이렇게 해야 잘못된 URL이나 GitHub 조회 실패, 내 기존 run attach 때문에
         * 무료 분석권이 잘못 차감되는 일을 막을 수 있습니다.
         */
        AnalysisAccessType analysisAccessType = membershipAccessService.grantAnalysisStart(owner);

        RepoRun run = new RepoRun(
                runId,
                owner,
                req.getRepoUrl(),
                parsed.getOwner(),
                parsed.getRepo(),
                ref,
                commitSha,
                wsRoot.toString(),
                analysisAccessType
        );

        repoRunRepository.save(run);

        try {
            pipelineQueueService.enqueue(run, userId);
        } catch (RuntimeException e) {
            /*
             * enqueue 실패 시에는 현재 요청이 잡 생성에 실패했으므로 락을 즉시 해제합니다.
             * 정상 경로에서는 worker가 진행하는 동안 TTL로 자연 만료되도록 유지합니다.
             */
            analysisCacheLockService.releaseIfOwned(analysisCacheKey, lockOwnerToken);
            throw e;
        }

        log.info(
                "Run queued runId={}, status={}, sha={}, accessType={}",
                runId,
                run.getStatus(),
                abbreviateSha(commitSha),
                analysisAccessType
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
     * 캐시 키 시드 구성 전용 메서드입니다.
     * <p>
     * 분리한 이유:
     * - createRun 본문에서 버전/옵션 조립 로직을 분리해 가독성과 유지보수성을 높입니다.
     * - 추후 옵션 축이 늘어나도 이 메서드만 수정하면 되도록 변경 지점을 고정합니다.
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
     * 캐시 hit sourceRun이 현재 사용자 소유인지 확인합니다.
     */
    private RepoRun resolveOwnedCachedRun(String sourceRunId, Long userId) {
        if (sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }

        return repoRunRepository.findOwnedRun(sourceRunId, userId)
                .orElse(null);
    }

    /**
     * 전역 캐시 수용을 위해 sourceRunId 존재 여부만 확인합니다.
     */
    private RepoRun resolveAnyCachedRun(String sourceRunId) {
        if (sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }

        return repoRunRepository.findById(sourceRunId)
                .orElse(null);
    }

    /**
     * 캐시 재사용 허용 조건(풀 성공)을 검사합니다.
     *
     * 허용 조건:
     * 1) repo_run.status == SUCCESS
     * 2) run_pipeline_job.status == SUCCESS
     *
     * 위 조건을 둘 다 만족하지 않으면 cache hit를 수용하지 않고 신규 분석 경로로 폴백합니다.
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
     * 원본 run 결과를 요청자 소유 run으로 복제합니다.
     *
     * 이렇게 구현한 이유:
     * - 단순히 원본 runId를 반환하면 owner 검증(progress/artifact)에서 차단됩니다.
     * - 요청자 소유 run으로 메타를 복제하면 기존 권한 모델을 유지한 채 전역 캐시를 재사용할 수 있습니다.
     */
    private RepoRun createSharedCachedRun(
            RepoRun sourceRun,
            User owner,
            Long requestUserId,
            AnalysisAccessType accessType
    ) {
        String sharedRunId = generateRunId();

        RepoRun sharedRun = new RepoRun(
                sharedRunId,
                owner,
                sourceRun.getRepoUrl(),
                sourceRun.getRepoOwner(),
                sourceRun.getRepoName(),
                sourceRun.getResolvedRef(),
                sourceRun.getCommitSha(),
                sourceRun.getWorkspaceRoot(),
                accessType
        );

        repoRunRepository.save(sharedRun);

        RunPipelineJob sharedJob = copyJobState(sourceRun.getRunId(), sharedRun, requestUserId);
        runPipelineJobRepository.save(sharedJob);

        copyStepExecutionSnapshots(sourceRun.getRunId(), sharedRun, sharedJob);
        copyArtifactsForSharedRun(sourceRun.getRunId(), sharedRun);

        return sharedRun;
    }

    /**
     * 원본 run의 최종 잡 상태를 복제합니다.
     * READY 캐시는 성공 결과를 전제로 하므로 기본값은 SUCCESS입니다.
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
     * 원본 run의 단계별 상태를 복제해 진행 상세 화면에서 동일한 단계를 확인할 수 있게 합니다.
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
                copied.skip("캐시 재사용으로 큐 대기 단계를 생략했습니다.");
            }

            runPipelineStepExecutionRepository.save(copied);
        }
    }

    /**
     * 원본 run의 산출물 메타를 요청자 소유 run으로 복제합니다.
     * S3 재업로드 없이 DB 레코드만 복제하므로 캐시 hit 지연이 매우 낮습니다.
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
     * Run 생성 응답을 표준 포맷으로 조립합니다.
     *
     * 왜 분리했는가:
     * - W07 요구사항(캐시 메타 응답)을 한 지점에서 일관되게 채우기 위함입니다.
     * - hit/miss/공유복제 경로가 달라도 응답 계약이 흔들리지 않게 합니다.
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
     * 타사용자 FULL 분석을 기다리기 위한 요청자 소유 run/job/step을 생성합니다.
     *
     * 구현 이유:
     * - 409 재시도 UX 대신 "내 runId"를 즉시 반환해 polling 흐름을 유지합니다.
     * - worker가 곧바로 실행하지 않도록 job은 RUNNING+무락(lock null) 대기 상태로 둡니다.
     */
    private RepoRun createCacheWaitingRun(
            RepoRunCreateRequest req,
            User owner,
            GithubRepoRef parsed,
            String ref,
            String commitSha,
            Long userId,
            AnalysisAccessType accessType
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
                waitingWsRoot.toString(),
                accessType
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
     * W09 락 경합 시, 동일 repo/sha의 진행중 job을 찾아 attach 가능 여부를 판단합니다.
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
     * build_manifest meta의 buildMode를 읽어 FULL 여부를 판단합니다.
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
     * Redis lock value(owner token) 생성 규칙입니다.
     * owner 검증 해제 시에 "누가 획득한 락인지"를 식별하기 위해 사용합니다.
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
