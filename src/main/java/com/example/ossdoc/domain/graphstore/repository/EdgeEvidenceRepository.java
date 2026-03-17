package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EdgeEvidenceRepository extends JpaRepository<EdgeEvidence, EdgeEvidenceId> {
}