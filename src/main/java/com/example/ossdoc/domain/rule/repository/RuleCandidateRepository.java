package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleCandidateRepository extends JpaRepository<RuleCandidate, Long> {

    boolean existsByRun_RunId(String runId);

    long countByRun_RunId(String runId);

    long countByRun_RunIdAndConfidence(
            String runId,
            RuleCandidateConfidence confidence
    );

    Optional<RuleCandidate> findByRun_RunIdAndRuleKey(
            String runId,
            String ruleKey
    );

    List<RuleCandidate> findAllByRun_RunId(String runId);

    List<RuleCandidate> findAllByRun_RunIdOrderByScoreDesc(String runId);

    List<RuleCandidate> findAllByRun_RunIdAndCandidateKind(
            String runId,
            RuleCandidateKind candidateKind
    );

    List<RuleCandidate> findAllByRun_RunIdAndStatus(
            String runId,
            RuleCandidateStatus status
    );

    List<RuleCandidate> findAllByRun_RunIdAndConfidence(
            String runId,
            RuleCandidateConfidence confidence
    );

    List<RuleCandidate> findAllByRun_RunIdAndPublicApiRelatedTrue(
            String runId
    );

    void deleteAllByRun_RunId(String runId);
}