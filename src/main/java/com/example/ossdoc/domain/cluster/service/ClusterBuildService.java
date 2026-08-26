package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.cluster.artifact.output.RankingsJson;
import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.SubsystemAssembler;
import com.example.ossdoc.domain.publicapi.service.PublicApiEntrySyncService;
import com.example.ossdoc.domain.publicapi.support.EntryPointJsonCodec;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repository 분석 결과를 기반으로 subsystem 군집화와 ranking을 수행하는 서비스.
 *
 * 전체 처리 순서는 다음과 같다.
 *
 * 1. RepoRun 조회
 * 2. Public API Entry 동기화
 * 3. ENTRY_POINTS_JSON 기반 refined entry point 조회
 * 4. GraphStore를 TYPE 중심 weighted graph로 projection
 * 5. 여러 resolution 후보에 대해 Leiden 수행
 * 6. 최적 community 결과를 subsystem으로 조립
 * 7. owner / exception 규칙을 이용한 subsystem refinement
 * 8. 최종 member 구성을 기준으로 deterministic subsystem ID 생성
 * 9. symbol / subsystem ranking 수행
 * 10. subsystems.json, rankings.json artifact 생성
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ClusterBuildService {

    private final RepoRunRepository repoRunRepository;
    private final GraphProjectionService graphProjectionService;
    private final ResolutionProbeService resolutionProbeService;
    private final SubsystemAssembler subsystemAssembler;
    private final SubsystemRefinerService subsystemRefinerService;

    /**
     * 최종 subsystem의 member 구성을 기준으로
     * 반복 실행에서도 안정적인 subsystem ID를 생성한다.
     */
    private final SubsystemIdentityService subsystemIdentityService;

    private final RankingService rankingService;
    private final ClusterArtifactPublisher clusterArtifactPublisher;
    private final PublicApiEntrySyncService publicApiEntrySyncService;
    private final ArtifactRepository artifactRepository;
    private final EntryPointJsonCodec entryPointJsonCodec;
    private final ClusterSignalProperties clusterSignalProperties;

    /**
     * run을 기준으로 clustering과 ranking을 수행하고
     * 최종 subsystem / ranking artifact를 생성한다.
     */
    public ClusterBuildResponse build(ClusterBuildRequest request) {

        /*
         * 1. 분석 대상 RepoRun 조회.
         *
         * 존재하지 않는 runId라면 이후 GraphStore나 Artifact를 조회할 수 없으므로
         * 가장 먼저 유효성을 검사한다.
         */
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() ->
                        new ClusterException(
                                ClusterErrorCode.CLUSTER_RUN_NOT_FOUND
                        )
                );

        /*
         * 2. Public API Entry 보장.
         *
         * clustering graph에서 entry point 정보가 ranking과 signal에 사용될 수 있으므로
         * TYPE 단위 public API entry를 먼저 동기화한다.
         */
        if (publicApiEntrySyncService.ensureTypeEntries(run).isEmpty()) {
            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_PUBLIC_API_EMPTY
            );
        }

        /*
         * 3. ENTRY_POINTS_JSON artifact에서
         * 신뢰도 기준을 통과한 refined entry point를 조회한다.
         *
         * artifact가 없다면 빈 Set을 반환하고,
         * API-flow signal은 자동으로 비활성화된다.
         */
        Set<String> refinedEntryIds =
                resolveRefinedEntryPoints(request.getRunId());

        /*
         * 4. GraphStore를 clustering용 graph로 projection한다.
         *
         * 개선된 GraphProjectionService에서는 다음 관계가 제외된다.
         *
         * - UNRESOLVED edge
         * - Reflection edge
         * - weight <= 0
         * - NaN / Infinity weight
         *
         * 원본 GraphStore edge 자체를 삭제하는 것은 아니다.
         */
        ProjectedGraph projectedGraph;

        try {
            projectedGraph = graphProjectionService.loadProjectedGraph(
                    request.getRunId(),
                    refinedEntryIds
            );

        } catch (ClusterException e) {
            throw e;

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] graph projection failed. runId={}",
                    request.getRunId(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_PROJECTION_FAILED
            );
        }

        /*
         * projection 결과에 TYPE node가 하나도 없다면
         * Leiden clustering을 수행할 수 없다.
         */
        if (projectedGraph.getNodes() == null
                || projectedGraph.getNodes().isEmpty()) {

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_GRAPH_EMPTY
            );
        }

        /*
         * 5. request parameter 기본값 설정.
         *
         * Jackson 역직렬화 과정에서 Lombok 필드 기본값이 적용되지 않고
         * null이 들어올 수 있기 때문에 명시적으로 기본값을 설정한다.
         */
        int minClusterSize =
                request.getMinClusterSize() != null
                        ? request.getMinClusterSize()
                        : 3;

        int iterations =
                request.getIterations() != null
                        ? request.getIterations()
                        : 10;

        int topK =
                request.getTopK() != null
                        ? request.getTopK()
                        : 30;

        /*
         * 6. 여러 resolution 후보에 대해 Leiden을 실행하고
         * 가장 적절한 community 결과를 선택한다.
         *
         * 개선 버전에서는 Leiden이 실제 사용하는 CPM Quality를
         * 중심 평가 기준으로 사용한다.
         *
         * Modularity는 결과 비교용 diagnostic metric으로 유지한다.
         */
        ResolutionProbeService.ProbeResult probeResult;

        try {
            probeResult = resolutionProbeService.findBest(
                    projectedGraph,
                    minClusterSize,
                    iterations
            );

        } catch (ClusterException e) {
            throw e;

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] Leiden probe failed. runId={}, nodes={}, edges={}",
                    run.getRunId(),
                    projectedGraph.getNodes().size(),
                    projectedGraph.getEdges().size(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_LEIDEN_FAILED
            );
        }

        if (probeResult == null) {
            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_LEIDEN_FAILED
            );
        }

        /*
         * 7. Leiden community 결과를 Subsystem으로 조립한다.
         *
         * 개선된 SubsystemAssembler에서는
         * minClusterSize 미만의 작은 cluster를 무조건 misc에 넣지 않는다.
         *
         * 작은 cluster가 정상 cluster와 연결되어 있다면
         * edge weight 합이 가장 큰 정상 cluster로 우선 흡수한다.
         *
         * 어디에도 연결되지 않은 작은 cluster만 misc로 이동한다.
         */
        List<Subsystem> subsystems;

        try {
            subsystems = subsystemAssembler.assemble(
                    projectedGraph,
                    probeResult.communityResult().getClusters(),
                    minClusterSize
            );

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] subsystem assembly failed. runId={}",
                    run.getRunId(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_ASSEMBLY_FAILED
            );
        }

        /*
         * 8. Leiden 결과에 deterministic structural rule을 적용한다.
         *
         * 현재 Refiner에서는 owner 흡수, exception 계보 등
         * graph clustering만으로 처리하기 어려운 구조 규칙을 후처리한다.
         */
        SubsystemRefinerService.RefineOutcome refineOutcome;

        try {
            refineOutcome = subsystemRefinerService.refine(
                    subsystems,
                    projectedGraph.getNodes(),
                    run.getRunId()
            );

            subsystems = refineOutcome.subsystems();

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] subsystem refine failed. runId={}",
                    run.getRunId(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_REFINE_FAILED
            );
        }

        /*
         * 9. Refiner까지 끝난 최종 member 구성을 기준으로
         * deterministic subsystem ID를 생성한다.
         *
         * 반드시 Refiner 이후에 실행해야 한다.
         *
         * Refiner 전에 ID를 생성하면 이후 member 이동으로 인해
         * subsystem ID와 실제 member 구성이 서로 달라질 수 있다.
         *
         * ID 생성 기준:
         *
         * commit SHA
         * +
         * 정렬된 memberSymbolIds
         *
         * 따라서 동일 commit에서 동일한 member 집합이라면
         * 반복 실행해도 동일 subsystem ID를 얻을 수 있다.
         */
        try {
            subsystems = subsystemIdentityService.rekey(
                    subsystems,
                    run.getCommitSha()
            );

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] subsystem identity generation failed. runId={}",
                    run.getRunId(),
                    e
            );

            /*
             * 별도의 Identity 전용 ErrorCode가 현재 존재하지 않으므로
             * subsystem 후처리 단계 오류로 취급한다.
             *
             * 향후 필요하면 CLUSTER_IDENTITY_FAILED 같은
             * 별도 ErrorCode를 추가할 수 있다.
             */
            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_REFINE_FAILED
            );
        }

        /*
         * 10. 최종 subsystem과 projected graph를 기반으로
         * symbol ranking과 subsystem ranking을 계산한다.
         */
        RankingService.RankingResult rankingResult;

        try {
            rankingResult = rankingService.rank(
                    projectedGraph,
                    subsystems,
                    topK
            );

        } catch (Exception e) {
            /*
             * ranking 실패 원인을 확인할 수 있도록
             * 실행 조건과 graph 규모를 함께 로그로 남긴다.
             */
            log.error(
                    "[CLUSTER] ranking failed. runId={}, topK={}, subsystemCount={}, nodeCount={}, edgeCount={}",
                    request.getRunId(),
                    topK,
                    subsystems.size(),
                    projectedGraph.getNodes() == null
                            ? 0
                            : projectedGraph.getNodes().size(),
                    projectedGraph.getEdges() == null
                            ? 0
                            : projectedGraph.getEdges().size(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_RANKING_FAILED
            );
        }

        /*
         * 11. 최종 metric과 artifact 생성.
         */
        try {
            /*
             * subsystem에 포함된 전체 TYPE node 수.
             */
            int totalNodeCount = subsystems.stream()
                    .mapToInt(
                            subsystem ->
                                    subsystem
                                            .getMemberSymbolIds()
                                            .size()
                    )
                    .sum();

            /*
             * misc subsystem에 속한 TYPE node 수.
             */
            int miscNodeCount = subsystems.stream()
                    .filter(
                            subsystem ->
                                    "misc".equals(
                                            subsystem.getName()
                                    )
                    )
                    .mapToInt(
                            subsystem ->
                                    subsystem
                                            .getMemberSymbolIds()
                                            .size()
                    )
                    .sum();

            /*
             * 전체 node 중 misc가 차지하는 비율.
             *
             * 낮을수록 작은 cluster들이 실제 subsystem으로
             * 적절하게 흡수되었을 가능성이 높다.
             *
             * 단, 무조건 낮다고 좋은 것은 아니므로
             * 실제 semantic cohesion과 함께 확인해야 한다.
             */
            double miscRatio =
                    totalNodeCount == 0
                            ? 0.0
                            : (double) miscNodeCount
                            / totalNodeCount;

            /*
             * subsystem 평균 크기.
             */
            double avgSize = subsystems.stream()
                    .mapToInt(
                            subsystem ->
                                    subsystem
                                            .getMemberSymbolIds()
                                            .size()
                    )
                    .average()
                    .orElse(0.0);

            /*
             * subsystem 크기 표준편차.
             *
             * 지나치게 큰 giant cluster와
             * 다수의 작은 cluster가 동시에 존재하면 값이 커질 수 있다.
             */
            double stddevSize =
                    subsystems.isEmpty()
                            ? 0.0
                            : Math.sqrt(
                            subsystems.stream()
                                    .mapToDouble(
                                            subsystem ->
                                                    Math.pow(
                                                            subsystem
                                                                    .getMemberSymbolIds()
                                                                    .size()
                                                                    - avgSize,
                                                            2
                                                    )
                                    )
                                    .average()
                                    .orElse(0.0)
                    );

            /*
             * clustering 품질 및 결과 분석용 metric.
             *
             * cpm_quality:
             * Leiden이 실제 사용하는 CPM 품질함수 값.
             *
             * modularity:
             * 기존 결과와의 비교 및 분석을 위해 유지하는 diagnostic metric.
             *
             * 두 값은 scale과 정의가 다르므로 직접 크기를 비교하면 안 된다.
             */
            Map<String, Object> metricsMap = Map.of(
                    "misc_node_count",
                    miscNodeCount,

                    "total_node_count",
                    totalNodeCount,

                    "misc_ratio",
                    Math.round(miscRatio * 1000.0) / 1000.0,

                    "subsystem_count",
                    subsystems.size(),

                    "avg_subsystem_size",
                    Math.round(avgSize * 10.0) / 10.0,

                    "stddev_subsystem_size",
                    Math.round(stddevSize * 100.0) / 100.0,

                    "modularity",
                    probeResult.modularity(),

                    "cpm_quality",
                    probeResult.cpmQuality(),

                    "leiden_resolution",
                    probeResult.resolution(),

                    "leiden_iterations",
                    iterations
            );

            /*
             * subsystem artifact 생성.
             *
             * Map.of()은 엔트리 개수 제한이 있기 때문에
             * algorithm metadata는 Map.ofEntries()를 사용한다.
             */
            SubsystemsJson subsystemsJson = SubsystemsJson.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .algorithm(
                            Map.ofEntries(
                                    Map.entry(
                                            "name",
                                            "Leiden"
                                    ),

                                    /*
                                     * 최종 선택된 CPM resolution.
                                     */
                                    Map.entry(
                                            "resolution",
                                            probeResult.resolution()
                                    ),

                                    Map.entry(
                                            "iterations",
                                            iterations
                                    ),

                                    /*
                                     * TYPE node 기반 weighted undirected graph에서
                                     * Leiden을 수행했음을 기록한다.
                                     */
                                    Map.entry(
                                            "graphMode",
                                            "UNDIRECTED_WEIGHTED_TYPE_GRAPH"
                                    ),

                                    /*
                                     * Leiden 후보 평가의 주 quality function.
                                     */
                                    Map.entry(
                                            "qualityFunction",
                                            "CPM"
                                    ),

                                    /*
                                     * Leiden이 최적화한 CPM Quality.
                                     */
                                    Map.entry(
                                            "cpmQuality",
                                            probeResult.cpmQuality()
                                    ),

                                    /*
                                     * 기존 결과 비교 및 분석용 Modularity.
                                     */
                                    Map.entry(
                                            "modularity",
                                            probeResult.modularity()
                                    ),

                                    /*
                                     * minClusterSize 이상의 유효 Leiden cluster 수.
                                     */
                                    Map.entry(
                                            "clusterCount",
                                            probeResult.clusterCount()
                                    ),

                                    /*
                                     * owner 흡수 / exception 계보 등
                                     * Refiner 처리 정보를 기록한다.
                                     */
                                    Map.entry(
                                            "refiner",
                                            refineOutcome.refinerMeta()
                                    ),

                                    /*
                                     * Package, API Flow 등
                                     * GraphProjection 단계에서 적용된 signal metadata.
                                     */
                                    Map.entry(
                                            "signals",
                                            projectedGraph.getSignalMeta() == null
                                                    ? Map.of()
                                                    : projectedGraph.getSignalMeta()
                                    ),

                                    /*
                                     * clustering 결과 품질 metric.
                                     */
                                    Map.entry(
                                            "metrics",
                                            metricsMap
                                    )
                            )
                    )

                    /*
                     * RankingService에서 score와 core symbol 등이
                     * 반영된 최종 subsystem을 artifact에 기록한다.
                     */
                    .subsystems(
                            rankingResult.enrichedSubsystems()
                    )
                    .build();

            /*
             * symbol / subsystem ranking artifact.
             */
            RankingsJson rankingsJson = RankingsJson.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .symbolRankings(
                            rankingResult.symbolRankings()
                    )
                    .subsystemRankings(
                            rankingResult.subsystemRankings()
                    )
                    .build();

            /*
             * 최종 artifact 저장.
             */
            Artifact subsystemsArtifact =
                    clusterArtifactPublisher.publishSubsystems(
                            run,
                            subsystemsJson
                    );

            Artifact rankingsArtifact =
                    clusterArtifactPublisher.publishRankings(
                            run,
                            rankingsJson
                    );

            /*
             * API 응답에는 artifact ID와 주요 결과 개수만 반환한다.
             */
            return new ClusterBuildResponse(
                    run.getRunId(),
                    subsystems.size(),
                    rankingResult.symbolRankings().size(),
                    rankingsArtifact.getArtifactId(),
                    subsystemsArtifact.getArtifactId()
            );

        } catch (ClusterException e) {
            throw e;

        } catch (Exception e) {
            log.error(
                    "[CLUSTER] artifact save failed. runId={}",
                    run.getRunId(),
                    e
            );

            throw new ClusterException(
                    ClusterErrorCode.CLUSTER_ARTIFACT_SAVE_FAILED
            );
        }
    }

    /**
     * ENTRY_POINTS_JSON artifact에서 신뢰도 기준을 통과한 symbolId를 읽는다.
     *
     * ENTRY_POINTS_JSON artifact가 존재하지 않으면 빈 Set을 반환한다.
     * 이 경우 GraphProjection 단계에서 API-flow signal이 자동으로 비활성화된다.
     */
    private Set<String> resolveRefinedEntryPoints(String runId) {

        /*
         * API-flow signal 설정에서
         * entry point 최소 confidence를 가져온다.
         */
        ClusterSignalProperties.ApiFlowSignal apiFlow =
                clusterSignalProperties
                        .getSignals()
                        .getApiFlow();

        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                        runId,
                        ArtifactKind.ENTRY_POINTS_JSON
                )
                .map(artifact -> {
                    /*
                     * ENTRY_POINTS_JSON에서 confidence 기준을 만족하는
                     * symbolId만 추출한다.
                     */
                    Set<String> ids =
                            entryPointJsonCodec.readSymbolIds(
                                    artifact.getMeta(),
                                    apiFlow.getMinConfidence()
                            );

                    log.info(
                            "[CLUSTER] ENTRY_POINTS_JSON loaded. runId={}, refinedEntryCount={}",
                            runId,
                            ids.size()
                    );

                    return ids;
                })
                .orElseGet(() -> {
                    /*
                     * ENTRYPOINT 단계가 실행되지 않았거나 실패했다면
                     * API-flow signal 없이 clustering을 계속 진행한다.
                     */
                    log.warn(
                            "[CLUSTER] ENTRY_POINTS_JSON not found. api-flow signal will auto-disable. runId={}",
                            runId
                    );

                    return Set.of();
                });
    }
}