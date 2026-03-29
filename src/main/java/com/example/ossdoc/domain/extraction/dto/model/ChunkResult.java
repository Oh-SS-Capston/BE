package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 청크 1건 처리 결과
 */
@Builder
public record ChunkResult(
        ChunkDescriptor descriptor,
        ChunkStatus status,
        Map<String, EvidenceFact> evidence,
        List<SymbolFact> symbols,
        List<RelationFact> relations,
        List<ObservationFact> observations,
        StatsMeta stats,
        List<String> warnings,
        List<String> errors
) {
    public ChunkResult {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        relations = relations == null ? List.of() : List.copyOf(relations);
        observations = observations == null ? List.of() : List.copyOf(observations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
