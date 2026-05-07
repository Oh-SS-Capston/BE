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
}
