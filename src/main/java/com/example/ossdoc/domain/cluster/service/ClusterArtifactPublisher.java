package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.cluster.dto.output.RankingsJsonDto;
import com.example.ossdoc.domain.cluster.dto.output.SubsystemsJsonDto;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClusterArtifactPublisher {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public Artifact publishRankings(RepoRun run, RankingsJsonDto dto) {
        try {
            JsonNode content = objectMapper.valueToTree(dto);
            return artifactService.saveJsonArtifact(
                    run,
                    ArtifactKind.RANKINGS_JSON,
                    "1.0",
                    "analysis/rankings.json",
                    content
            );
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_ARTIFACT_SAVE_FAILED);
        }
    }

    public Artifact publishSubsystems(RepoRun run, SubsystemsJsonDto dto) {
        try {
            JsonNode content = objectMapper.valueToTree(dto);
            return artifactService.saveJsonArtifact(
                    run,
                    ArtifactKind.SUBSYSTEMS_JSON,
                    "1.0",
                    "analysis/subsystems.json",
                    content
            );
        } catch (ClusterException e) {
            throw e;
        } catch (Exception e) {
            throw new ClusterException(ClusterErrorCode.CLUSTER_ARTIFACT_SAVE_FAILED);
        }
    }
}