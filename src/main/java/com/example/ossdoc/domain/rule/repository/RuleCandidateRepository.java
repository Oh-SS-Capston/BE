package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    List<RuleCandidate> findAllByRun_RunIdAndRuleKeyIn(
            String runId,
            Collection<String> ruleKeys
    );

    void deleteAllByRun_RunId(String runId);

    /**
     * forceRebuild 시 rule candidate를 엔티티 단위로 하나씩 삭제하지 않고 bulk delete 한다.
     * 단, RuleCandidateEvidence가 candidate를 참조하므로 evidence 삭제 후 호출해야 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from RuleCandidate c
            where c.run.runId = :runId
            """)
    void deleteByRunIdBulk(@Param("runId") String runId);

    /**
     * /mine 응답 생성 시 confidence별 count를 한 번의 group by 쿼리로 가져온다.
     * 기존 countByRun... 4회 호출을 줄이기 위한 메서드.
     */
    @Query("""
            select c.confidence, count(c)
            from RuleCandidate c
            where c.run.runId = :runId
            group by c.confidence
            """)
    List<Object[]> countGroupByConfidence(@Param("runId") String runId);

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