package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RuleMiningSignalRepository extends JpaRepository<RuleMiningSignal, Long> {

    boolean existsByRun_RunId(String runId);

    boolean existsByRun_RunIdAndSignalType(String runId, RuleMiningSignalType signalType);

    long countByRun_RunId(String runId);

    List<RuleMiningSignal> findAllByRun_RunId(String runId);

    List<RuleMiningSignal> findAllByRun_RunIdAndSignalType(
            String runId,
            RuleMiningSignalType signalType
    );

    List<RuleMiningSignal> findAllByRun_RunIdAndSignalTypeIn(
            String runId,
            Collection<RuleMiningSignalType> signalTypes
    );

    List<RuleMiningSignal> findAllByRun_RunIdAndSymbol_SymbolId(
            String runId,
            String symbolId
    );

    List<RuleMiningSignal> findAllByRun_RunIdAndEdge_EdgeId(
            String runId,
            Long edgeId
    );

    List<RuleMiningSignal> findAllByRun_RunIdAndEvidence_EvidenceId(
            String runId,
            Long evidenceId
    );

    /**
     * run 범위에서 가장 최근에 생성된 룰 신호 1건을 조회한다.
     */
    Optional<RuleMiningSignal> findTopByRun_RunIdOrderByCreatedAtDesc(String runId);

    /**
     * 메서드 기능 카드 생성을 위해 signal + symbol + sourceFile을 한 번에 로드한다.
     */
    @Query("""
            select s
            from RuleMiningSignal s
            left join fetch s.symbol sym
            left join fetch sym.sourceFile sf
            where s.run.runId = :runId
              and s.symbol is not null
            """)
    List<RuleMiningSignal> findAllWithSymbolAndSourceByRunId(@Param("runId") String runId);

    void deleteAllByRun_RunId(String runId);
}
