// domain/artifact/service/ArtifactService.java
package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactRepository artifactRepository;

    @Transactional
    public Artifact saveJsonArtifact(RepoRun run, ArtifactKind kind, String schemaVersion, String path, JsonNode meta) {
        Artifact artifact = new Artifact(
                null,
                run,
                kind,
                schemaVersion,        // 예: "0.1"
                "application/json",
                path,
                meta
        );
        return artifactRepository.save(artifact);
    }
}