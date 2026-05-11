package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

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
}