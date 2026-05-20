package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.dto.response.RepoRunRecentResponse;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLookupService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
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

        /*
         * [1단계 캐시 연동]
         * 아직 캐시 조회/재사용은 적용하지 않고, 캐시 키 생성 규격만 실제 실행 경로에 연결합니다.
         * 이렇게 먼저 연결해두면 운영 로그에서 키 안정성을 검증한 뒤, 다음 단계에서 Redis/DB 조회를 안전하게 붙일 수 있습니다.
         */
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

        /*
         * [W06 캐시 조회 연결]
         * cache hit이면 정적 분석 파이프라인을 다시 실행하지 않고,
         * 기존 run 결과를 즉시 반환해 프런트에서 바로 산출물을 조회할 수 있게 합니다.
         */
        AnalysisCacheLookupResult cacheLookupResult = analysisCacheLookupService.lookupReady(
                analysisCacheKey,
                normalizedRepoUrl,
                commitSha
        );

        if (cacheLookupResult.hit()) {
            RepoRun cachedRun = resolveOwnedCachedRun(cacheLookupResult.sourceRunId(), userId);
            if (cachedRun != null) {
                log.info(
                        "[CACHE] hit accepted. requestSha={}, sourceRunId={}, reason={}",
                        abbreviateSha(commitSha),
                        cachedRun.getRunId(),
                        cacheLookupResult.reason()
                );
                return toCreateResponse(cachedRun);
            }

            /*
             * cache hit이더라도 현재 사용자 소유 run이 아니면 진행 조회 권한에서 막히므로
             * 안전하게 miss로 폴백해 신규 분석을 실행합니다.
             */
            log.info(
                    "[CACHE] hit ignored due to ownership mismatch. sourceRunId={}, userId={}",
                    cacheLookupResult.sourceRunId(),
                    userId
            );
        } else {
            log.info(
                    "[CACHE] miss. sha={}, reason={}",
                    abbreviateSha(commitSha),
                    cacheLookupResult.reason()
            );
        }

        String runId = "run_"
                + OffsetDateTime.now().toLocalDate().toString().replace("-", "")
                + "_"
                + UUID.randomUUID().toString().substring(0, 8);

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

        /*
         * 프론트가 build/extraction/graphstore/cluster/classMap API를 직접 호출하지 않도록,
         * run 생성과 동시에 pipeline job을 큐에 넣습니다.
         */
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
     * 왜 분리했는가:
     * - createRun 본문에서 버전/옵션 조립 로직을 분리해 가독성과 유지보수성을 높입니다.
     * - 추후 옵션 항목이 늘어나도 이 메서드만 수정하면 되도록 변경 지점을 고정합니다.
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
     * 권한이 맞지 않으면 null을 반환해 신규 분석 경로로 폴백합니다.
     */
    private RepoRun resolveOwnedCachedRun(String sourceRunId, Long userId) {
        if (sourceRunId == null || sourceRunId.isBlank()) {
            return null;
        }
        return repoRunRepository.findByRunIdAndOwner_Id(sourceRunId, userId)
                .orElse(null);
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
}
