package com.example.ossdoc.domain.rule.repository;

import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RuleMiningSignalRepository extends JpaRepository<RuleMiningSignal, Long> {

    boolean existsByRun_RunId(String runId);

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

    void deleteAllByRun_RunId(String runId);
}