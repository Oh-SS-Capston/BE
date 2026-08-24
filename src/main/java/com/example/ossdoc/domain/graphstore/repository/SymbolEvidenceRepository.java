package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEvidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEvidenceId;
import com.example.ossdoc.domain.graphstore.model.projection.SymbolEvidenceLinkKeyRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SymbolEvidenceRepository extends JpaRepository<SymbolEvidence, SymbolEvidenceId> {

    List<SymbolEvidence> findAllBySymbol_Run_RunId(String runId);

    /**
     * symbol_evidence 중복 방지에는 복합키만 필요하므로 link 엔티티 전체 로딩을 피한다.
     */
    @Query("""
            select new com.example.ossdoc.domain.graphstore.model.projection.SymbolEvidenceLinkKeyRow(
                se.id.symbolId,
                se.id.evidenceId
            )
            from SymbolEvidence se
            where se.symbol.run.runId = :runId
            """)
    List<SymbolEvidenceLinkKeyRow> findLinkKeysByRunId(@Param("runId") String runId);

    List<SymbolEvidence> findAllBySymbol_SymbolIdIn(Collection<String> symbolIds);

    @Query("SELECT se FROM SymbolEvidence se JOIN FETCH se.evidence WHERE se.symbol.run.runId = :runId")
    List<SymbolEvidence> findAllWithEvidenceByRunId(@Param("runId") String runId);

    @Query("""
            SELECT se.symbol.symbolId, COUNT(se)
            FROM SymbolEvidence se
            WHERE se.symbol.run.runId = :runId
            GROUP BY se.symbol.symbolId
            """)
    List<Object[]> countBySymbolIdForRun(@Param("runId") String runId);
}
