package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.build.service.BuildResolveService;
import com.example.ossdoc.domain.build.dto.response.BuildResolveResponse;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.classmap.dto.request.ClassMapBuildRequest;
import com.example.ossdoc.domain.classmap.service.ClassMapBuildService;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.request.SuperClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterParameterRecommendResponse;
import com.example.ossdoc.domain.cluster.service.ClusterBuildService;
import com.example.ossdoc.domain.cluster.service.ClusterParameterRecommendService;
import com.example.ossdoc.domain.cluster.service.SuperClusterBuildService;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.publicapi.service.ApiMapBuildService;
import com.example.ossdoc.domain.publicapi.service.EntryPointBuildService;
import com.example.ossdoc.domain.extraction.facade.FactsExtractionFacade;
import com.example.ossdoc.domain.graphstore.dto.request.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.service.GraphStoreIngestService;
import com.example.ossdoc.domain.rule.dto.request.RuleCandidateMineRequest;
import com.example.ossdoc.domain.rule.service.RuleCandidateMiningService;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLockService;
import com.example.ossdoc.domain.run.cache.service.AnalysisCachePublishService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.enums.RunStage;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeySeed;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/*
 * 실제 파이프라인 실행 오케스트레이터입니다.
 *
 * 필수 단계:
 * SNAPSHOT → BUILD → EXTRACTION → GRAPHSTORE
 *
 * 선택 단계:
 * CLUSTER → CLASSMAP → RULE → LLM
 *
 * 필수 단계 실패: FAILED
 * 선택 단계 실패: PARTIAL_SUCCESS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunPipelineExecutor {

    private final RunPipelineJobRepository jobRepository;
    private final RunPipelineStepService stepService;

    private final RunSnapshotService runSnapshotService;
    private final BuildResolveService buildResolveService;
    private final FactsExtractionFacade factsExtractionFacade;
    private final GraphStoreIngestService graphStoreIngestService;

    private final ClusterParameterRecommendService clusterParameterRecommendService;
    private final EntryPointBuildService entryPointBuildService;
    private final ClusterBuildService clusterBuildService;
    private final SuperClusterBuildService superClusterBuildService;
    private final ClusterSignalProperties clusterSignalProperties;
    private final ApiMapBuildService apiMapBuildService;
    private final ClassMapBuildService classMapBuildService;

    private final RuleCandidateMiningService ruleCandidateMiningService;
    private final LlmService llmService;
    private final AnalysisCachePublishService analysisCachePublishService;
    private final AnalysisCacheLockService analysisCacheLockService;
    private final RepoRunRepository repoRunRepository;
    private final RunAnalysisCacheKeyFactory runAnalysisCacheKeyFactory;
    private final AnalysisCacheProperties analysisCacheProperties;

    public void execute(Long jobId) {
        RunPipelineJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RunException(RunErrorCode.PIPELINE_JOB_NOT_FOUND));

        String runId = job.getRun().getRunId();
        Long userId = job.getUserId();

        log.info("[PIPELINE] execute start. jobId={}, runId={}", jobId, runId);

        List<String> optionalFailures = new ArrayList<>();
        AtomicReference<BuildMode> buildModeRef = new AtomicReference<>(null);

        try {
            executeRequired(
                    jobId,
                    RunStage.SNAPSHOT,
                    "레포지토리 스냅샷을 준비 중입니다.",
                    () -> runSnapshotService.prepareSnapshot(runId)
            );

            executeRequired(
                    jobId,
                    RunStage.BUILD,
                    "빌드 환경을 분석 중입니다.",
                    () -> {
                        BuildResolveResponse response = buildResolveService.resolve(runId);
                        buildModeRef.set(response == null ? null : response.getBuildMode());
                    }
            );

            executeRequired(
                    jobId,
                    RunStage.EXTRACTION,
                    "소스 코드와 바이트코드를 분석 중입니다.",
                    () -> factsExtractionFacade.extract(
                            FactsExtractRequest.builder()
                                    .runId(runId)
                                    .mode(null)
                                    .includeObservations(true)
                                    .includeTests(false)
                                    .failFast(false)
                                    .targetModules(List.of())
                                    .includeGlobs(List.of())
                                    .excludeGlobs(List.of())
                                    .build()
                    )
            );

            executeRequired(
                    jobId,
                    RunStage.GRAPHSTORE,
                    "코드 구조 그래프를 저장 중입니다.",
                    () -> graphStoreIngestService.ingest(
                            GraphStoreIngestRequest.builder()
                                    .runId(runId)
                                    .artifactId(null)
                                    .build(),
                            userId
                    )
            );

            /*
             * GRAPHSTORE 완료 후 실제 그래프 규모를 기준으로
             * CLUSTER / CLASSMAP 추천 파라미터를 계산합니다.
             */
            PipelineAnalysisParameters parameters =
                    resolveRecommendedParameters(runId);

            executeOptional(
                    jobId,
                    RunStage.ENTRYPOINT,
                    "공개 API 진입점을 탐지 중입니다.",
                    "공개 API 진입점 탐지에 실패했습니다.",
                    optionalFailures,
                    () -> entryPointBuildService.build(runId)
            );

            boolean clusterSucceeded = executeOptional(
                    jobId,
                    RunStage.CLUSTER,
                    "주요 모듈과 군집을 분석 중입니다.",
                    "군집화 생성에 실패했습니다.",
                    optionalFailures,
                    () -> clusterBuildService.build(
                            ClusterBuildRequest.builder()
                                    .runId(runId)
                                    .resolution(parameters.resolution())
                                    .iterations(parameters.iterations())
                                    .minClusterSize(parameters.minClusterSize())
                                    .topK(parameters.topK())
                                    .build()
                    )
            );

            if (clusterSucceeded && clusterSignalProperties.getSuperCluster().isEnabled()) {
                executeOptional(
                        jobId,
                        RunStage.SUPER_CLUSTER,
                        "모듈 수퍼 클러스터를 생성 중입니다.",
                        "수퍼 클러스터 생성에 실패했습니다.",
                        optionalFailures,
                        () -> superClusterBuildService.build(
                                SuperClusterBuildRequest.builder()
                                        .runId(runId)
                                        .build()
                        )
                );
            } else {
                stepService.skipStep(
                        jobId,
                        RunStage.SUPER_CLUSTER,
                        clusterSucceeded
                                ? "super-cluster 설정이 비활성화되어 있습니다. (ossdoc.cluster.super-cluster.enabled=true 로 활성화)"
                                : "군집화 생성 실패로 수퍼 클러스터 생성을 건너뜁니다."
                );
            }

            executeOptional(
                    jobId,
                    RunStage.PUBLICAPI,
                    "공개 API 진입점·확장 포인트를 분석 중입니다.",
                    "API map 생성에 실패했습니다.",
                    optionalFailures,
                    () -> apiMapBuildService.build(runId)
            );

            executeOptional(
                    jobId,
                    RunStage.CLASSMAP,
                    "클래스 다이어그램을 생성 중입니다.",
                    "클래스 다이어그램 생성에 실패했습니다.",
                    optionalFailures,
                    () -> classMapBuildService.build(
                            ClassMapBuildRequest.builder()
                                    .runId(runId)
                                    .maxNodes(parameters.maxNodes())
                                    .maxEdges(parameters.maxEdges())
                                    .startHereTopN(parameters.startHereTopN())
                                    .build(),
                            userId
                    )
            );

            /*
             * LLM 자동 입력 조립에는 rule_candidates.json이 필요하므로,
             * LLM 전에 RULE 단계를 먼저 실행합니다.
             */
            boolean ruleSucceeded = executeOptional(
                    jobId,
                    RunStage.RULE,
                    "규칙 후보를 추출 중입니다.",
                    "규칙 후보 생성에 실패했습니다.",
                    optionalFailures,
                    () -> ruleCandidateMiningService.mine(
                            new RuleCandidateMineRequest(
                                    runId,
                                    null,
                                    null
                            ),
                            userId
                    )
            );

            /*
             * RULE이 실패하면 LLM 입력 전제가 깨지므로,
             * LLM을 억지로 실행하지 않고 SKIPPED로 남깁니다.
             */
            if (ruleSucceeded) {
                executeOptional(
                        jobId,
                        RunStage.LLM,
                        "LLM 분석 결과를 생성 중입니다.",
                        "LLM 결과 생성에 실패했습니다.",
                        optionalFailures,
                        () -> llmService.refine(
                                new LlmRequest(
                                        runId,
                                        null,
                                        null,
                                        true,
                                        true
                                )
                        )
                );
            } else {
                stepService.skipStep(
                        jobId,
                        RunStage.LLM,
                        "규칙 후보 생성 실패로 LLM 결과 생성을 건너뛰었습니다."
                );
            }

            BuildMode buildMode = buildModeRef.get();
            if (buildMode == BuildMode.FAILED) {
                stepService.markRunFailed(
                        jobId,
                        "빌드 분석에 실패해 전체 분석을 완료할 수 없습니다.",
                        "buildMode=FAILED"
                );
                publishFailedCacheSafely(job, "buildMode=FAILED");
                return;
            }

            /*
             * 최종 상태 정책:
             * - FULL + 선택 단계 전부 성공 => SUCCESS
             * - COMPILE_ONLY/SOURCE_ONLY/UNKNOWN 또는 선택 단계 일부 실패 => PARTIAL_SUCCESS
             */
            if (optionalFailures.isEmpty() && buildMode == BuildMode.FULL) {
                stepService.markRunSuccess(jobId);
                publishReadyCacheSafely(job);
            } else {
                List<String> partialReasons = new ArrayList<>(optionalFailures);
                if (buildMode != BuildMode.FULL) {
                    partialReasons.add(buildModeToPartialReason(buildMode));
                }
                stepService.markRunPartialSuccess(
                        jobId,
                        String.join(" / ", partialReasons)
                );
                publishFailedCacheSafely(job, String.join(" / ", partialReasons));
            }
        } catch (Exception e) {
            log.error("[PIPELINE] Required step failed. jobId={}, runId={}", jobId, runId, e);

            String internalError = safeInternalError(e);
            stepService.markRunFailed(
                    jobId,
                    "필수 분석 단계 실행 중 오류가 발생했습니다.",
                    internalError
            );
            publishFailedCacheSafely(job, internalError);
        } finally {
            releaseAnalysisLockSafely(runId);
        }
    }

    /**
     * 추천 서비스가 정상 동작하면 추천값을 사용하고,
     * 추천 계산 자체가 실패해도 파이프라인이 멈추지 않도록
     * 기존 기본값으로 fallback 합니다.
     */
    private PipelineAnalysisParameters resolveRecommendedParameters(String runId) {
        try {
            ClusterParameterRecommendResponse recommendation =
                    clusterParameterRecommendService.recommend(runId);

            ClusterParameterRecommendResponse.ClusterRecommended cluster =
                    recommendation.getClusterRecommended();

            ClusterParameterRecommendResponse.ClassMapRecommended classMap =
                    recommendation.getClassMapRecommended();

            log.info(
                    """
                    [PIPELINE] recommended parameters resolved. \
                    runId={}, sizeTier={}, typeSymbolCount={}, edgeCount={}, edgePerType={}, \
                    cluster=[resolution={}, iterations={}, minClusterSize={}, topK={}], \
                    classMap=[maxNodes={}, maxEdges={}, startHereTopN={}]
                    """,
                    runId,
                    recommendation.getSizeTier(),
                    recommendation.getTypeSymbolCount(),
                    recommendation.getEdgeCount(),
                    recommendation.getEdgePerType(),
                    cluster.getResolution(),
                    cluster.getIterations(),
                    cluster.getMinClusterSize(),
                    cluster.getTopK(),
                    classMap.getMaxNodes(),
                    classMap.getMaxEdges(),
                    classMap.getStartHereTopN()
            );

            return new PipelineAnalysisParameters(
                    cluster.getResolution(),
                    cluster.getIterations(),
                    cluster.getMinClusterSize(),
                    cluster.getTopK(),
                    classMap.getMaxNodes(),
                    classMap.getMaxEdges(),
                    classMap.getStartHereTopN()
            );
        } catch (Exception e) {
            log.warn(
                    "[PIPELINE] parameter recommendation failed. fallback defaults will be used. runId={}",
                    runId,
                    e
            );

            return PipelineAnalysisParameters.fallbackDefaults();
        }
    }

    private String safeInternalError(Exception e) {
        Throwable root = e;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();

        if (message == null || message.isBlank()) {
            message = e.getMessage();
        }

        if (message == null || message.isBlank()) {
            return "분석 파이프라인 실행 중 알 수 없는 오류가 발생했습니다.";
        }

        int limit = 4000;
        return message.length() <= limit ? message : message.substring(0, limit);
    }

    private void executeRequired(
            Long jobId,
            RunStage stage,
            String message,
            PipelineRunnable runnable
    ) {
        log.info("[PIPELINE] required step start. jobId={}, stage={}", jobId, stage);

        stepService.startStep(jobId, stage, message);

        try {
            runnable.run();
            stepService.succeedStep(jobId, stage, stage.getDefaultMessage());
        } catch (Exception e) {
            stepService.failStep(jobId, stage, safeMessage(e));
            throw e;
        }
    }

    private boolean executeOptional(
            Long jobId,
            RunStage stage,
            String message,
            String failureMessage,
            List<String> optionalFailures,
            PipelineRunnable runnable
    ) {
        log.info("[PIPELINE] optional step start. jobId={}, stage={}", jobId, stage);

        stepService.startStep(jobId, stage, message);

        try {
            runnable.run();
            stepService.succeedStep(jobId, stage, stage.getDefaultMessage());
            return true;
        } catch (Exception e) {
            log.warn("[PIPELINE] Optional step failed. stage={}, jobId={}", stage, jobId, e);

            stepService.failStep(jobId, stage, failureMessage);
            optionalFailures.add(failureMessage);
            return false;
        }
    }

    private String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "분석 파이프라인 실행 중 오류가 발생했습니다.";
        }

        return e.getMessage();
    }

    private String buildModeToPartialReason(BuildMode buildMode) {
        if (buildMode == null) {
            return "빌드 품질을 확인할 수 없어 부분 성공으로 처리했습니다.";
        }
        return switch (buildMode) {
            case COMPILE_ONLY -> "빌드 결과가 COMPILE_ONLY라 부분 성공으로 처리했습니다.";
            case SOURCE_ONLY -> "빌드 결과가 SOURCE_ONLY라 부분 성공으로 처리했습니다.";
            case FAILED -> "빌드 결과가 FAILED라 부분 성공으로 처리했습니다.";
            case FULL -> "빌드 결과가 FULL입니다.";
        };
    }

    /**
     * W08: 파이프라인이 풀 성공한 경우 READY 캐시를 발행합니다.
     *
     * 실패 처리 정책:
     * - 캐시 발행 실패는 파이프라인 본 성공 상태를 뒤집지 않습니다.
     * - 대신 경고 로그를 남기고 다음 요청에서 miss -> 재분석 경로로 안전 폴백합니다.
     */
    private void publishReadyCacheSafely(RunPipelineJob job) {
        try {
            boolean published = analysisCachePublishService.publishReady(job.getRun().getRunId());
            if (!published) {
                log.warn(
                        "[CACHE] READY publish skipped. runId={}, reason=REQUIRED_ARTIFACT_MISSING_OR_INVALID",
                        job.getRun().getRunId()
                );
            }
        } catch (Exception e) {
            log.warn(
                    "[CACHE] READY publish failed. runId={}",
                    job.getRun().getRunId(),
                    e
            );
        }
    }

    /**
     * W10: 파이프라인 실패/부분성공 시 FAILED 캐시를 안전하게 발행합니다.
     *
     * 실패 처리 정책:
     * - FAILED 캐시 발행 실패가 본 파이프라인 상태를 뒤집지 않도록 예외는 삼킵니다.
     * - 본 분석 상태는 stepService가 이미 기록했으므로, 여기서는 쿨다운 메타만 보강합니다.
     */
    private void publishFailedCacheSafely(RunPipelineJob job, String reason) {
        try {
            analysisCachePublishService.publishFailed(job.getRun().getRunId(), reason);
        } catch (Exception e) {
            log.warn(
                    "[CACHE] FAILED publish failed. runId={}",
                    job.getRun().getRunId(),
                    e
            );
        }
    }

    /**
     * W09 보강:
     * - enqueue 시 획득한 분석 락을 파이프라인 종료(성공/부분성공/실패) 시점에 즉시 해제합니다.
     * - TTL 만료를 기다리지 않고 다음 동일 요청이 READY hit로 바로 전환되도록 지연을 줄입니다.
     * - owner token 검증 해제를 사용해, 다른 실행이 선점한 락은 지우지 않도록 보호합니다.
     */
    private void releaseAnalysisLockSafely(String runId) {
        try {
            RepoRun run = repoRunRepository.findById(runId).orElse(null);
            if (run == null) {
                log.warn("[CACHE][LOCK] release skipped. run not found. runId={}", runId);
                return;
            }

            String cacheKey = buildCacheKey(run);
            String ownerToken = AnalysisCacheLockService.ownerTokenForRun(runId);
            analysisCacheLockService.releaseIfOwned(cacheKey, ownerToken);
        } catch (Exception e) {
            log.warn("[CACHE][LOCK] release failed. runId={}", runId, e);
        }
    }

    /**
     * RepoRun 정보를 캐시 키 시드로 직렬화해 enqueue 시점과 동일한 cacheKey를 재구성합니다.
     */
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

    /**
     * 파이프라인에서 실제로 사용할 군집화/클래스맵 파라미터 묶음입니다.
     */
    private record PipelineAnalysisParameters(
            double resolution,
            int iterations,
            int minClusterSize,
            int topK,
            int maxNodes,
            int maxEdges,
            int startHereTopN
    ) {
        private static PipelineAnalysisParameters fallbackDefaults() {
            return new PipelineAnalysisParameters(
                    0.012,
                    10,
                    3,
                    30,
                    120,
                    240,
                    8
            );
        }
    }

    @FunctionalInterface
    private interface PipelineRunnable {
        void run();
    }
}
