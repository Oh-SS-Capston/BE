package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RuleCandidateEvidenceRepository extends JpaRepository<RuleCandidateEvidence, Long> {

    List<RuleCandidateEvidence> findAllByCandidate_Run_RunId(String runId);

    List<RuleCandidateEvidence> findAllByCandidate_CandidateId(Long candidateId);

    List<RuleCandidateEvidence> findAllByCandidate_CandidateIdIn(Collection<Long> candidateIds);

    List<RuleCandidateEvidence> findAllBySignal_SignalId(Long signalId);

    List<RuleCandidateEvidence> findAllByEvidence_EvidenceId(Long evidenceId);

    List<RuleCandidateEvidence> findAllByEdge_EdgeId(Long edgeId);

    void deleteAllByCandidate_Run_RunId(String runId);

    void deleteAllByCandidate_CandidateId(Long candidateId);

    /**
     * artifact 발행 시 candidate / signal / evidence / edge를 함께 가져온다.
     * candidate별 evidence lazy loading 비용을 줄인다.
     */
    @Query("""
            select rce
            from RuleCandidateEvidence rce
            join fetch rce.candidate c
            left join fetch rce.signal
            left join fetch rce.evidence
            left join fetch rce.edge
            where c.candidateId in :candidateIds
            """)
    List<RuleCandidateEvidence> findAllWithRelationsByCandidateIdIn(
            @Param("candidateIds") Collection<Long> candidateIds
    );
}