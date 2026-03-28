package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.MergeStage;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * module 단위 병합 결과
 */
@Builder
public record ModuleMergeResult(
        String module,
        MergeStage stage,
        List<RootMergeResult> roots,
        Map<String, EvidenceFact> evidence,
        List<SymbolFact> symbols,
        List<RelationFact> relations,
        List<ObservationFact> observations,
        StatsMeta stats,
        List<String> warnings
) {
    public ModuleMergeResult {
        roots = roots == null ? List.of() : List.copyOf(roots);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        relations = relations == null ? List.of() : List.copyOf(relations);
        observations = observations == null ? List.of() : List.copyOf(observations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
