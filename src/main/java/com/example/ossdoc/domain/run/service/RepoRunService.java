package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLookupService;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.dto.response.RepoRunRecentResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.enums.PipelineJobStatus;
import com.example.ossdoc.domain.run.enums.PipelineStepStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoRunService {

    private final RepoRunRepository repoRunRepository;
    private final UserRepository userRepository;
    private final GithubClient githubClient;
    private final WorkspaceManager workspaceManager;
    private final RunPipelineQueueService pipelineQueueService;
    private final RunAnalysisCacheKeyFactory runAnalysisCacheKeyFactory;
    private final AnalysisCacheLookupService analysisCacheLookupService;
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

        AnalysisCacheLookupResult cacheLookupResult = analysisCacheLookupService.lookupReady(
                analysisCacheKey,
                normalizedRepoUrl,
                commitSha
        );

        if (cacheLookupResult.hit()) {
            RepoRun ownedCachedRun = resolveOwnedCachedRun(cacheLookupResult.sourceRunId(), userId);
            if (ownedCachedRun != null) {
                log.info(
                        "[CACHE] hit accepted. requestSha={}, sourceRunId={}, reason={}",
                        abbreviateSha(commitSha),
                        ownedCachedRun.getRunId(),
                        cacheLookupResult.reason()
                );
                return toCreateResponse(ownedCachedRun);
            }

            RepoRun sourceRun = resolveAnyCachedRun(cacheLookupResult.sourceRunId());
            if (sourceRun != null) {
                RepoRun sharedCachedRun = createSharedCachedRun(sourceRun, owner, userId);
                log.info(
                        "[CACHE] global hit accepted. sourceRunId={}, sharedRunId={}, requestUserId={}",
                        sourceRun.getRunId(),
                        sharedCachedRun.getRunId(),
                        userId
                );
                return toCreateResponse(sharedCachedRun);
            }

            log.warn(
                    "[CACHE] hit payload exists but source run is missing. sourceRunId={}, fallback=MISS",
                    cacheLookupResult.sourceRunId()
            );
        } else {
            log.info(
                    "[CACHE] miss. sha={}, reason={}",
                    abbreviateSha(commitSha),
                    cacheLookupResult.reason()
            );
        }

        String runId = generateRunId();

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

        pipelineQueueService.enqueue(run, userId);

        log.info(
                "Run queued runId={}, status={}, sha={}",
                runId,
                run.getStatus(),
                abbreviateSha(commitSha)
        );

        return toCreateResponse(run);
    }

    @Transactional(readOnly = true)
    public List<RepoRunRecentResponse> getRecentRuns(Long userId) {
        return repoRunRepository.findTop10ByOwner_IdOrderByCreatedAtDesc(userId)
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
        return repoRunRepository.findByRunIdAndOwner_Id(sourceRunId, userId)
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
     * 원본 run 결과를 요청자 소유 run으로 복제합니다.
     *
     * 이렇게 구현한 이유:
     * - 단순히 원본 runId를 반환하면 owner 검증(progress/artifact)에서 차단됩니다.
     * - 요청자 소유 run으로 메타를 복제하면 기존 권한 모델을 유지한 채 전역 캐시를 재사용할 수 있습니다.
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
     * 원본 run의 최종 잡 상태를 복제합니다.
     * READY 캐시는 성공 결과를 전제로 하므로 기본값은 SUCCESS입니다.
     */
    private RunPipelineJob copyJobState(String sourceRunId, RepoRun sharedRun, Long requestUserId) {
        RunPipelineJob sharedJob = RunPipelineJob.create(sharedRun, requestUserId);
        sharedJob.markSuccess();

        RunPipelineJob sourceJob = runPipelineJobRepository.findByRun_RunId(sourceRunId)
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
                runPipelineStepExecutionRepository.findAllByRun_RunIdOrderByStepIdAsc(sourceRunId);

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

    private RepoRunCreateResponse toCreateResponse(RepoRun run) {
        return RepoRunCreateResponse.builder()
                .runId(run.getRunId())
                .status(run.getStatus())
                .commitSha(run.getCommitSha())
                .workspaceRoot(run.getWorkspaceRoot())
                .build();
    }

    private String abbreviateCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return "<empty>";
        }
        return cacheKey.length() <= 12 ? cacheKey : cacheKey.substring(0, 12);
    }

    private String generateRunId() {
        return "run_"
                + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
                + "_"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
