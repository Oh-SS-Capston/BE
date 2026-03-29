package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    Optional<Evidence> findFirstByRun_RunIdAndHash(String runId, String hash);

    List<Evidence> findByRun_RunIdAndTypeAndStartLineAndEndLineAndSnippet(
            String runId,
            EvidenceType type,
            Integer startLine,
            Integer endLine,
            String snippet
    );
}