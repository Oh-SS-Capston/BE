package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<RuleCandidate> findAllByRun_RunIdAndPublicApiRelatedTrue(String runId);

    void deleteAllByRun_RunId(String runId);

    /**
     * rule_candidates.json 발행 시 subjectSymbol lazy loading을 줄인다.
     * graphstore 도메인 수정 없이 rule 도메인 조회만 최적화한다.
     */
    @Query("""
            select c
            from RuleCandidate c
            left join fetch c.subjectSymbol
            where c.run.runId = :runId
            order by
                case when c.score is null then 1 else 0 end,
                c.score desc,
                c.candidateId asc
            """)
    List<RuleCandidate> findAllWithSubjectByRunIdOrderByScoreDesc(
            @Param("runId") String runId
    );
}