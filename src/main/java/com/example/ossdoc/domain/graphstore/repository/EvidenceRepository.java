package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    /**
     * 성능 최적화를 위해 run 범위 evidence를 한 번에 로드한다.
     */
    List<Evidence> findAllByRun_RunId(String runId);

    Optional<Evidence> findFirstByRun_RunIdAndHash(
            String runId,
            String hash
    );

    List<Evidence> findByRun_RunIdAndEvidenceTypeAndStartLineAndEndLineAndSnippet(
            String runId,
            EvidenceType evidenceType,
            Integer startLine,
            Integer endLine,
            String snippet
    );

    /**
     * 파일까지 일치하는 evidence 중복 여부를 확인한다.
     */
    List<Evidence> findByRun_RunIdAndEvidenceTypeAndFile_FileIdAndStartLineAndEndLineAndSnippet(
            String runId,
            EvidenceType evidenceType,
            Long fileId,
            Integer startLine,
            Integer endLine,
            String snippet
    );
}
