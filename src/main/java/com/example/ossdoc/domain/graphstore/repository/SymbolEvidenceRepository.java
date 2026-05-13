package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEvidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SymbolEvidenceRepository extends JpaRepository<SymbolEvidence, SymbolEvidenceId> {

    List<SymbolEvidence> findAllBySymbol_Run_RunId(String runId);

    List<SymbolEvidence> findAllBySymbol_SymbolIdIn(Collection<String> symbolIds);

    @Query("SELECT se FROM SymbolEvidence se JOIN FETCH se.evidence WHERE se.symbol.run.runId = :runId")
    List<SymbolEvidence> findAllWithEvidenceByRunId(@Param("runId") String runId);
}
