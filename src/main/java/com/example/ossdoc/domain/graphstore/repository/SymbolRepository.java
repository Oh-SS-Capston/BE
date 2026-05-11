package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SymbolRepository extends JpaRepository<SymbolEntity, String> {

    Optional<SymbolEntity> findByRun_RunIdAndQualifiedName(String runId, String qualifiedName);

    /**
     * 성능 최적화를 위해 run 범위 symbol을 한 번에 로드한다.
     */
    List<SymbolEntity> findAllByRun_RunId(String runId);

    List<SymbolEntity> findAllByRun_RunIdAndSymbolKind(String runId, SymbolKind symbolkind);

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
