package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SymbolRepository extends JpaRepository<SymbolEntity, String> {

    Optional<SymbolEntity> findByRun_RunIdAndQualifiedName(String runId, String qualifiedName);

    //추가 : 특정 run에 속한 symbol들 중에서, 특정 종류(symbolKind)만 전부 조회
    List<SymbolEntity> findAllByRun_RunIdAndSymbolKind(String runId, SymbolKind symbolkind);
}