package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.model.projection.EdgeLookupRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * GraphStore ingest 중복 판별에 필요한 edge key 필드만 조회한다.
     *
     * <p>기존 findAllByRun_RunId는 Edge 엔티티 전체와 jsonb/연관 엔티티를 영속성 컨텍스트에 올린다.
     * projection 조회로 기존 edge의 식별 정보만 읽고, evidence 연결 시 필요한 edge만 reference로 연결한다.</p>
     */
    @Query("""
            select new com.example.ossdoc.domain.graphstore.model.projection.EdgeLookupRow(
                e.edgeId,
                e.edgeType,
                fromSymbol.symbolId,
                toSymbol.symbolId,
                e.toRawRef
            )
            from Edge e
            join e.fromSymbol fromSymbol
            left join e.toSymbol toSymbol
            where e.run.runId = :runId
            """)
    List<EdgeLookupRow> findLookupRowsByRunId(@Param("runId") String runId);

    /**
     * run 범위의 edge 총량을 집계한다.
     * - 그래프 밀도(대략) 계산에 사용해 추천 파라미터를 보정한다.
     */
    long countByRun_RunId(String runId);

    List<Edge> findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(String runId, EdgeType edgeType);

    List<Edge> findAllByRun_RunIdAndEdgeTypeIn(String runId, List<EdgeType> edgeTypes);

    Optional<Edge> findTopByRun_RunIdOrderByUpdatedAtDesc(String runId);
}
