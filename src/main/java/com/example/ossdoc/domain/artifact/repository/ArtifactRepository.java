package com.example.ossdoc.domain.artifact.repository;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    Optional<Artifact> findTopByRun_RunIdAndKindOrderByCreatedAtDesc(String runId, ArtifactKind kind);

    /**
     * 동일 run/kind/path 산출물이 이미 존재하는지 조회한다.
     * cluster 재실행 시 upsert 처리를 위해 사용한다.
     */
    Optional<Artifact> findByRun_RunIdAndKindAndPath(String runId, ArtifactKind kind, String path);

    /**
     * 전역 캐시 hit 시 원본 run의 산출물을 요청자 소유 run으로 복제할 때 사용합니다.
     */
    List<Artifact> findAllByRun_RunIdOrderByArtifactIdAsc(String runId);
}
