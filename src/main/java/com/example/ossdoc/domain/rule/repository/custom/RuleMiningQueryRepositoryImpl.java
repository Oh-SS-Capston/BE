package com.example.ossdoc.domain.rule.repository.custom;

import com.example.ossdoc.domain.rule.dto.projection.AssertionCallProjection;
import com.example.ossdoc.domain.rule.dto.projection.GuardReturnProjection;
import com.example.ossdoc.domain.rule.dto.projection.GuardThrowProjection;
import com.example.ossdoc.domain.rule.dto.projection.PersistenceActionProjection;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.jpa.HibernateHints;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class RuleMiningQueryRepositoryImpl implements RuleMiningQueryRepository {

    private static final int MAX_GUARD_LINE_DISTANCE = 8;

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<GuardThrowProjection> findGuardThrowSignals(String runId) {
        List<RuleMiningSignal> conditions = findSignalsByTypes(
                runId,
                List.of(RuleMiningSignalType.IF_CONDITION)
        );

        List<RuleMiningSignal> throwSignals = findSignalsByTypes(
                runId,
                List.of(RuleMiningSignalType.THROW_STATEMENT)
        );

        Map<String, List<RuleMiningSignal>> throwsBySymbol = groupBySymbolId(throwSignals);
        List<GuardThrowProjection> result = new ArrayList<>();

        for (RuleMiningSignal condition : conditions) {
            String symbolId = symbolId(condition);
            if (symbolId == null) {
                continue;
            }

            List<RuleMiningSignal> candidates = throwsBySymbol.getOrDefault(symbolId, List.of());

            for (RuleMiningSignal throwSignal : candidates) {
                int distance = lineDistance(condition, throwSignal);

                if (!isNearEnough(distance)) {
                    continue;
                }

                result.add(new GuardThrowProjection(
                        condition.getSymbol(),
                        condition,
                        throwSignal,
                        condition.getEvidence(),
                        throwSignal.getEvidence(),
                        distance
                ));
            }
        }

        return result;
    }

    @Override
    public List<GuardReturnProjection> findGuardReturnSignals(String runId) {
        List<RuleMiningSignal> conditions = findSignalsByTypes(
                runId,
                List.of(RuleMiningSignalType.IF_CONDITION)
        );

        List<RuleMiningSignal> returnSignals = findSignalsByTypes(
                runId,
                List.of(RuleMiningSignalType.RETURN_STATEMENT)
        );

        List<RuleMiningSignal> errorSignals = findSignalsByTypes(
                runId,
                List.of(RuleMiningSignalType.ERROR_RESPONSE)
        );

        Map<String, List<RuleMiningSignal>> returnsBySymbol = groupBySymbolId(returnSignals);
        Map<String, List<RuleMiningSignal>> errorsBySymbol = groupBySymbolId(errorSignals);

        List<GuardReturnProjection> result = new ArrayList<>();

        for (RuleMiningSignal condition : conditions) {
            String symbolId = symbolId(condition);
            if (symbolId == null) {
                continue;
            }

            List<RuleMiningSignal> returnCandidates = returnsBySymbol.getOrDefault(symbolId, List.of());
            if (returnCandidates.isEmpty()) {
                continue;
            }

            /*
             * 기존 코드에서는 return 후보마다 nearbyErrorSignal을 다시 계산했다.
             * condition 기준으로 1회만 계산해서 재사용한다.
             */
            RuleMiningSignal nearbyErrorSignal = findNearestSignal(
                    condition,
                    errorsBySymbol.getOrDefault(symbolId, List.of())
            );

            for (RuleMiningSignal returnSignal : returnCandidates) {
                int distance = lineDistance(condition, returnSignal);

                if (!isNearEnough(distance)) {
                    continue;
                }

                result.add(new GuardReturnProjection(
                        condition.getSymbol(),
                        condition,
                        returnSignal,
                        nearbyErrorSignal,
                        condition.getEvidence(),
                        returnSignal.getEvidence(),
                        nearbyErrorSignal == null ? null : nearbyErrorSignal.getEvidence(),
                        distance
                ));
            }
        }

        return result;
    }

    @Override
    public List<PersistenceActionProjection> findPersistenceActionSignals(String runId) {
        List<RuleMiningSignal> actionSignals = findSignalsByTypes(
                runId,
                List.of(
                        RuleMiningSignalType.REPOSITORY_SAVE,
                        RuleMiningSignalType.REPOSITORY_UPDATE,
                        RuleMiningSignalType.REPOSITORY_DELETE
                )
        );

        return actionSignals.stream()
                .map(signal -> new PersistenceActionProjection(
                        signal.getSymbol(),
                        signal,
                        signal.getSignalType(),
                        signal.getEdge(),
                        signal.getEvidence(),
                        signal.getNormalizedText()
                ))
                .toList();
    }

    @Override
    public List<AssertionCallProjection> findAssertionCallSignals(String runId) {
        List<RuleMiningSignal> assertionSignals = findSignalsByTypes(
                runId,
                List.of(
                        RuleMiningSignalType.ASSERTION_CALL,
                        RuleMiningSignalType.REQUIRE_CALL
                )
        );

        return assertionSignals.stream()
                .map(signal -> new AssertionCallProjection(
                        signal.getSymbol(),
                        signal,
                        signal.getSignalType(),
                        signal.getEdge(),
                        signal.getEvidence(),
                        signal.getNormalizedText()
                ))
                .toList();
    }

    @Override
    public List<RuleMiningSignal> findSignalsByTypes(
            String runId,
            List<RuleMiningSignalType> signalTypes
    ) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }

        if (signalTypes == null || signalTypes.isEmpty()) {
            return List.of();
        }

        /*
         * 기존 쿼리에서는 edge.fromSymbol, edge.toSymbol까지 항상 fetch join 했다.
         * miner에서는 대부분 signal.symbol, signal.edge id, signal.evidence, evidence.file 정도만 필요하다.
         * 그래서 불필요한 fetch join을 줄인다.
         */
        return em.createQuery("""
                        select s
                        from RuleMiningSignal s
                        left join fetch s.symbol
                        left join fetch s.edge
                        left join fetch s.evidence ev
                        left join fetch ev.file
                        where s.run.runId = :runId
                          and s.signalType in :signalTypes
                        order by s.symbol.symbolId asc nulls last,
                                 s.startLine asc nulls last,
                                 s.signalId asc
                        """, RuleMiningSignal.class)
                .setParameter("runId", runId)
                .setParameter("signalTypes", signalTypes)
                .setHint(HibernateHints.HINT_READ_ONLY, true)
                .getResultList();
    }

    private Map<String, List<RuleMiningSignal>> groupBySymbolId(List<RuleMiningSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return Map.of();
        }

        return signals.stream()
                .filter(signal -> symbolId(signal) != null)
                .collect(Collectors.groupingBy(this::symbolId));
    }

    private RuleMiningSignal findNearestSignal(
            RuleMiningSignal base,
            List<RuleMiningSignal> candidates
    ) {
        if (base == null || candidates == null || candidates.isEmpty()) {
            return null;
        }

        return candidates.stream()
                .filter(candidate -> isNearEnough(lineDistance(base, candidate)))
                .min(Comparator.comparingInt(candidate -> lineDistance(base, candidate)))
                .orElse(null);
    }

    private boolean isNearEnough(int distance) {
        return distance >= 0 && distance <= MAX_GUARD_LINE_DISTANCE;
    }

    /**
     * 기존 Math.abs(rightLine - leftLine)는 조건문보다 위에 있는 throw/return도 매칭할 수 있었다.
     * guard 패턴은 일반적으로 condition 이후 action이 나와야 하므로 방향성을 유지한다.
     */
    private int lineDistance(RuleMiningSignal left, RuleMiningSignal right) {
        if (left == null || right == null) {
            return Integer.MAX_VALUE;
        }

        Integer leftLine = firstNonNull(left.getStartLine(), left.getEndLine());
        Integer rightLine = firstNonNull(right.getStartLine(), right.getEndLine());

        if (leftLine == null || rightLine == null) {
            return Integer.MAX_VALUE;
        }

        return rightLine - leftLine;
    }

    private Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    private String symbolId(RuleMiningSignal signal) {
        if (signal == null || signal.getSymbol() == null) {
            return null;
        }

        return signal.getSymbol().getSymbolId();
    }
}