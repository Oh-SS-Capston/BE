package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from RuleCandidateEvidence rce
            where rce.candidate.candidateId = :candidateId
            """)
    void deleteByCandidateIdBulk(@Param("candidateId") Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from RuleCandidateEvidence rce
            where rce.candidate.candidateId in :candidateIds
            """)
    void deleteByCandidateIdInBulk(@Param("candidateIds") Collection<Long> candidateIds);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from RuleCandidateEvidence rce
            where rce.candidate.candidateId in (
                select c.candidateId
                from RuleCandidate c
                where c.run.runId = :runId
            )
            """)
    void deleteByRunIdBulk(@Param("runId") String runId);

    @Query("""
            select rce
            from RuleCandidateEvidence rce
            join fetch rce.candidate c
            left join fetch rce.signal
            left join fetch rce.evidence ev
            left join fetch ev.file
            left join fetch rce.edge
            where c.candidateId in :candidateIds
            """)
    List<RuleCandidateEvidence> findAllWithRelationsByCandidateIdIn(
            @Param("candidateIds") Collection<Long> candidateIds
    );
}