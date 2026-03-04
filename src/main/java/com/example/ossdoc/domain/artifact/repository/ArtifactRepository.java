// domain/artifact/repository/ArtifactRepository.java
package com.example.ossdoc.domain.artifact.repository;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {
}