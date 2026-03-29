package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EdgeRepository extends JpaRepository<Edge, Long> {

    Optional<Edge> findFirstByRun_RunIdAndFromSymbol_SymbolIdAndTypeAndToSymbol_SymbolId(
            String runId,
            String fromSymbolId,
            EdgeType type,
            String toSymbolId
    );

    List<Edge> findByRun_RunIdAndFromSymbol_SymbolIdAndTypeAndToSymbolIsNull(
            String runId,
            String fromSymbolId,
            EdgeType type
    );
}