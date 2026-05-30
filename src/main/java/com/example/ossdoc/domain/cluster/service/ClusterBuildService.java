package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.cluster.artifact.output.RankingsJson;
import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
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
    private final RankingService rankingService;
    private final ClusterArtifactPublisher clusterArtifactPublisher;
    private final PublicApiEntrySyncService publicApiEntrySyncService;
    private final ArtifactRepository artifactRepository;
    private final EntryPointJsonCodec entryPointJsonCodec;
    private final ClusterSignalProperties clusterSignalProperties;

    /**
     * run 기준 public_api_entry를 보장한 뒤 군집화/랭킹 산출물을 생성한다.
     */
    public ClusterBuildResponse build(ClusterBuildRequest request) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new ClusterException(ClusterErrorCode.CLUSTER_RUN_NOT_FOUND));

        if (publicApiEntrySyncService.ensureTypeEntries(run).isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PUBLIC_API_EMPTY);
        }

        Set<String> refinedEntryIds = resolveRefinedEntryPoints(request.getRunId());

        ProjectedGraph projectedGraph;
        try {
            projectedGraph = graphProjectionService.loadProjectedGraph(request.getRunId(), refinedEntryIds);
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        if (projectedGraph.getNodes() == null || projectedGraph.getNodes().isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        // Jackson 역직렬화 시 Lombok 필드 기본값이 누락되면 null로 들어와 언박싱 NPE가 발생하므로 호출 직전에 가드한다.
        int minClusterSize = request.getMinClusterSize() != null ? request.getMinClusterSize() : 3;
        int iterations     = request.getIterations()     != null ? request.getIterations()     : 10;
        int topK           = request.getTopK()           != null ? request.getTopK()           : 30;

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
            log.error("[CLUSTER] Leiden probe failed. runId={}, nodes={}, edges={}",
                    run.getRunId(), projectedGraph.getNodes().size(), projectedGraph.getEdges().size(), e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }

        if (probeResult == null) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }

        List<Subsystem> subsystems;
        try {
            subsystems = subsystemAssembler.assemble(
                    projectedGraph,
                    probeResult.communityResult().getClusters(),
                    minClusterSize
            );
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_ASSEMBLY_FAILED);
        }

        // Leiden 결과를 결정적 구조 규칙(owner 흡수 / exception 계보)으로 후처리한다.
        SubsystemRefinerService.RefineOutcome refineOutcome;
        try {
            refineOutcome = subsystemRefinerService.refine(
                    subsystems,
                    projectedGraph.getNodes(),
                    run.getRunId()
            );
            subsystems = refineOutcome.subsystems();
        } catch (Exception e) {
            log.error("[CLUSTER] subsystem refine failed. runId={}", run.getRunId(), e);
            throw new ClusterException(ClusterErrorCode.CLUSTER_REFINE_FAILED);
        }

        RankingService.RankingResult rankingResult;
        try {
            rankingResult = rankingService.rank(
                    projectedGraph,
                    subsystems,
                    topK
            );
        } catch (Exception e) {
            // 랭킹 실패의 실제 원인을 로그로 남겨 후속 장애 분석 속도를 높인다.
            log.error(
                    "[CLUSTER] ranking failed. runId={}, topK={}, subsystemCount={}, nodeCount={}, edgeCount={}",
                    request.getRunId(),
                    topK,
                    subsystems.size(),
                    projectedGraph.getNodes() == null ? 0 : projectedGraph.getNodes().size(),
                    projectedGraph.getEdges() == null ? 0 : projectedGraph.getEdges().size(),
                    e
            );
            throw new ClusterException(ClusterErrorCode.CLUSTER_RANKING_FAILED);
        }

        try {
            int totalNodeCount = subsystems.stream()
                    .mapToInt(s -> s.getMemberSymbolIds().size())
                    .sum();
            int miscNodeCount = subsystems.stream()
                    .filter(s -> "misc".equals(s.getName()))
                    .mapToInt(s -> s.getMemberSymbolIds().size())
                    .sum();
            double miscRatio = totalNodeCount == 0 ? 0.0 : (double) miscNodeCount / totalNodeCount;
            double avgSize = subsystems.stream()
                    .mapToInt(s -> s.getMemberSymbolIds().size())
                    .average()
                    .orElse(0.0);
            double stddevSize = subsystems.isEmpty() ? 0.0 : Math.sqrt(
                    subsystems.stream()
                            .mapToDouble(s -> Math.pow(s.getMemberSymbolIds().size() - avgSize, 2))
                            .average()
                            .orElse(0.0));

            Map<String, Object> metricsMap = Map.of(
                    "misc_node_count", miscNodeCount,
                    "total_node_count", totalNodeCount,
                    "misc_ratio", Math.round(miscRatio * 1000.0) / 1000.0,
                    "subsystem_count", subsystems.size(),
                    "avg_subsystem_size", Math.round(avgSize * 10.0) / 10.0,
                    "stddev_subsystem_size", Math.round(stddevSize * 100.0) / 100.0,
                    "modularity", probeResult.modularity(),
                    "leiden_resolution", probeResult.resolution(),
                    "leiden_iterations", iterations
            );

            SubsystemsJson subsystemsJson = SubsystemsJson.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .algorithm(Map.of(
                            "name", "Leiden",
                            "resolution", probeResult.resolution(),
                            "iterations", iterations,
                            "graphMode", "UNDIRECTED_WEIGHTED_TYPE_GRAPH",
                            "modularity", probeResult.modularity(),
                            "clusterCount", probeResult.clusterCount(),
                            "refiner", refineOutcome.refinerMeta(),
                            "signals", projectedGraph.getSignalMeta() == null
                                    ? Map.of()
                                    : projectedGraph.getSignalMeta(),
                            "metrics", metricsMap
                    ))
                    .subsystems(subsystems)
                    .build();

            RankingsJson rankingsJson = RankingsJson.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .symbolRankings(rankingResult.symbolRankings())
                    .subsystemRankings(rankingResult.subsystemRankings())
                    .build();

            Artifact subsystemsArtifact = clusterArtifactPublisher.publishSubsystems(run, subsystemsJson);
            Artifact rankingsArtifact = clusterArtifactPublisher.publishRankings(run, rankingsJson);

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
            throw new ClusterException(ClusterErrorCode.CLUSTER_ARTIFACT_SAVE_FAILED);
        }
    }

    /**
     * ENTRY_POINTS_JSON artifact에서 minConfidence 이상 symbolId를 읽는다.
     * artifact 부재 시(ENTRYPOINT 단계 실패/스킵) 빈 set을 반환해 5순위 provider가 auto-disable되도록 한다.
     */
    private Set<String> resolveRefinedEntryPoints(String runId) {
        ClusterSignalProperties.ApiFlowSignal apiFlow =
                clusterSignalProperties.getSignals().getApiFlow();

        return artifactRepository
                .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.ENTRY_POINTS_JSON)
                .map(a -> {
                    Set<String> ids = entryPointJsonCodec.readSymbolIds(
                            a.getMeta(), apiFlow.getMinConfidence());
                    log.info("[CLUSTER] ENTRY_POINTS_JSON loaded. runId={}, refinedEntryCount={}", runId, ids.size());
                    return ids;
                })
                .orElseGet(() -> {
                    log.warn("[CLUSTER] ENTRY_POINTS_JSON not found. api-flow signal will auto-disable. runId={}", runId);
                    return Set.of();
                });
    }
}
