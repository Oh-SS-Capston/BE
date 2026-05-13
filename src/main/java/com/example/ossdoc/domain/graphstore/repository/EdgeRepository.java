package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EdgeRepository extends JpaRepository<Edge, Long> {

    Optional<Edge> findFirstByRun_RunIdAndFromSymbol_SymbolIdAndEdgeTypeAndToSymbol_SymbolId(
            String runId,
            String fromSymbolId,
            EdgeType edgeType,
            String toSymbolId
    );

    List<Edge> findByRun_RunIdAndFromSymbol_SymbolIdAndEdgeTypeAndToSymbolIsNull(
            String runId,
            String fromSymbolId,
            EdgeType edgeType
    );

    List<Edge> findAllByRun_RunId(String runId);

    /**
     * run 범위의 edge 총량을 집계한다.
     * - 그래프 밀도(대략) 계산에 사용해 추천 파라미터를 보정한다.
     */
    long countByRun_RunId(String runId);

    List<Edge> findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(String runId, EdgeType edgeType);

    List<Edge> findAllByRun_RunIdAndEdgeTypeIn(String runId, List<EdgeType> edgeTypes);

    Optional<Edge> findTopByRun_RunIdOrderByUpdatedAtDesc(String runId);
}
