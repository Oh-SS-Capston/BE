package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SymbolRepository extends JpaRepository<SymbolEntity, String> {

    Optional<SymbolEntity> findByRun_RunIdAndQualifiedName(String runId, String qualifiedName);

    /**
     * 성능 최적화를 위해 run 범위 symbol을 한 번에 로드한다.
     */
    List<SymbolEntity> findAllByRun_RunId(String runId);

    /**
     * GraphStore ingest에서 이번 facts에 등장한 symbol만 관리 엔티티로 조회한다.
     *
     * <p>symbol은 module/sourceFile/owner/source span 갱신이 필요하므로 projection으로 대체하지 않는다.
     * 대신 run 전체 symbol을 올리지 않고 현재 facts의 qualified name 범위로 제한해 대형 프로젝트의 heap peak를 낮춘다.</p>
     */
    @Query("""
            select s
            from SymbolEntity s
            left join fetch s.sourceFile
            where s.run.runId = :runId
              and s.qualifiedName in :qualifiedNames
            """)
    List<SymbolEntity> findAllForIngestByRunIdAndQualifiedNameIn(
            @Param("runId") String runId,
            @Param("qualifiedNames") Set<String> qualifiedNames
    );

    List<SymbolEntity> findAllByRun_RunIdAndSymbolKind(String runId, SymbolKind symbolkind);

    /**
     * symbol_source_index 생성 시 owner/sourceFile 접근 N+1을 줄이기 위한 fetch 조회.
     */
    @Query("""
            select s
            from SymbolEntity s
            left join fetch s.owner
            left join fetch s.sourceFile
            where s.run.runId = :runId
            """)
    List<SymbolEntity> findAllWithOwnerAndSourceByRunId(@Param("runId") String runId);

    /**
     * 군집화 투영 단계에서 노드 인덱스를 재현 가능하게 만들기 위해 정렬된 순서로 조회한다.
     */
    List<SymbolEntity> findAllByRun_RunIdAndSymbolKindOrderBySymbolIdAsc(String runId, SymbolKind symbolKind);

    /**
     * run 범위에서 특정 심볼 kind의 개수를 집계한다.
     * - 군집화/클래스맵 파라미터 추천 시 그래프 크기 판단의 기준값으로 사용한다.
     */
    long countByRun_RunIdAndSymbolKind(String runId, SymbolKind symbolKind);

    /**
     * run 범위에서 마지막으로 갱신된 symbol 1건을 조회한다.
     */
    Optional<SymbolEntity> findTopByRun_RunIdOrderByUpdatedAtDesc(String runId);
}
