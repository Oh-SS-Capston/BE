// 역할: edge와 evidence 연결 정보를 조회해 관계 근거를 재구성한다.
package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EdgeEvidenceRepository extends JpaRepository<EdgeEvidence, EdgeEvidenceId> {
    List<EdgeEvidence> findAllByEdge_Run_RunId(String runId);

    List<EdgeEvidence> findAllByEdge_EdgeIdIn(Collection<Long> edgeIds);
}
