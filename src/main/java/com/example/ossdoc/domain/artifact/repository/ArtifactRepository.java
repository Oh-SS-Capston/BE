package com.example.ossdoc.domain.artifact.repository;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    Optional<Artifact> findTopByRun_RunIdAndKindOrderByCreatedAtDesc(String runId, ArtifactKind kind);
}