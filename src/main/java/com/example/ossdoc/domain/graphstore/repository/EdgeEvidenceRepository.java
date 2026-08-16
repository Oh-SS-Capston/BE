// 역할: edge와 evidence 연결 정보를 조회해 관계 근거를 재구성한다.
package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import com.example.ossdoc.domain.graphstore.model.projection.EdgeEvidenceLinkKeyRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EdgeEvidenceRepository extends JpaRepository<EdgeEvidence, EdgeEvidenceId> {
    List<EdgeEvidence> findAllByEdge_Run_RunId(String runId);

    /**
     * edge_evidence 중복 방지에는 복합키만 필요하므로 link 엔티티 전체 로딩을 피한다.
     */
    @Query("""
            select new com.example.ossdoc.domain.graphstore.model.projection.EdgeEvidenceLinkKeyRow(
                ee.id.edgeId,
                ee.id.evidenceId
            )
            from EdgeEvidence ee
            where ee.edge.run.runId = :runId
            """)
    List<EdgeEvidenceLinkKeyRow> findLinkKeysByRunId(@Param("runId") String runId);

    List<EdgeEvidence> findAllByEdge_EdgeIdIn(Collection<Long> edgeIds);
}
