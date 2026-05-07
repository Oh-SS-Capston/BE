package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.dto.response.ArtifactJsonResponse;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.exception.ArtifactException;
import com.example.ossdoc.domain.artifact.exception.code.ArtifactErrorCode;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArtifactQueryService {

    private final ArtifactRepository artifactRepository;

    public ArtifactJsonResponse getJsonArtifact(Long artifactId, Long userId) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));

        validateOwner(artifact, userId);
        validateJsonArtifact(artifact);

        return ArtifactJsonResponse.from(artifact);
    }

    private void validateOwner(Artifact artifact, Long userId) {
        RepoRun run = artifact.getRun();

        if (run.getOwner() == null || !Objects.equals(run.getOwner().getId(), userId)) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_FORBIDDEN);
        }
    }

    private void validateJsonArtifact(Artifact artifact) {
        if (!"application/json".equalsIgnoreCase(artifact.getContentType())) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_JSON);
        }
    }
}