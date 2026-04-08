package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SymbolRepository extends JpaRepository<SymbolEntity, String> {

    Optional<SymbolEntity> findByRun_RunIdAndQualifiedName(String runId, String qualifiedName);
}