package com.example.ossdoc.domain.extraction.dto.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * composer 직전 최종 집계 결과
 */
@Builder
public record ExtractionAggregate(
        Map<String, EvidenceFact> evidence,
        SymbolTable symbols,
        RelationTable relations,
        ObservationTable observations,
        StatsMeta stats,
        List<String> warnings
) {
    public ExtractionAggregate {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
