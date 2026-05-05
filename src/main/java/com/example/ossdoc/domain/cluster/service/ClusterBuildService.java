package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.cluster.dto.output.RankingsJsonDto;
import com.example.ossdoc.domain.cluster.dto.output.SubsystemsJsonDto;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.SubsystemAssembler;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class ClusterBuildService {

    private final RepoRunRepository repoRunRepository;
    private final GraphProjectionService graphProjectionService;
    private final LeidenCommunityService leidenCommunityService;
    private final SubsystemAssembler subsystemAssembler;
    private final RankingService rankingService;
    private final ClusterArtifactPublisher clusterArtifactPublisher;

    public ClusterBuildResponse build(ClusterBuildRequest request) {
        RepoRun run = repoRunRepository.findById(request.getRunId())
                .orElseThrow(() -> new ClusterException(ClusterErrorCode.CLUSTER_RUN_NOT_FOUND));

        ProjectedGraph projectedGraph;
        try {
            projectedGraph = graphProjectionService.loadProjectedGraph(request.getRunId());
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_PROJECTION_FAILED);
        }

        if (projectedGraph.getNodes() == null || projectedGraph.getNodes().isEmpty()) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_GRAPH_EMPTY);
        }

        CommunityResult communityResult;
        try {
            communityResult = leidenCommunityService.detect(
                    projectedGraph,
                    request.getResolution(),
                    request.getIterations()
            );
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_LEIDEN_FAILED);
        }

        List<Subsystem> subsystems;
        try {
            subsystems = subsystemAssembler.assemble(
                    projectedGraph,
                    communityResult.getClusters(),
                    request.getMinClusterSize()
            );
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_ASSEMBLY_FAILED);
        }

        RankingService.RankingResult rankingResult;
        try {
            rankingResult = rankingService.rank(
                    projectedGraph,
                    subsystems,
                    request.getTopK()
            );
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_RANKING_FAILED);
        }

        try {
            SubsystemsJsonDto subsystemsJson = SubsystemsJsonDto.builder()
                    .schemaVersion("1.0")
                    .runId(run.getRunId())
                    .generatedAt(OffsetDateTime.now())
                    .algorithm(Map.of(
                            "name", "Leiden",
                            "resolution", request.getResolution(),
                            "iterations", request.getIterations(),
                            "graphMode", "UNDIRECTED_WEIGHTED_TYPE_GRAPH"
                    ))
                    .subsystems(subsystems)
                    .build();

            RankingsJsonDto rankingsJson = RankingsJsonDto.builder()
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
}