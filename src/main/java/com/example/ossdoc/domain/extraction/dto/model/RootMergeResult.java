package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.MergeStage;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * sourceRoot / bytecodeRoot 단위 병합 결과
 */
@Builder
public record RootMergeResult(
        String module,
        String rootPath,
        ChunkKind kind,
        MergeStage stage,
        List<ChunkResult> chunkResults,
        Map<String, EvidenceFact> evidence,
        List<SymbolFact> symbols,
        List<RelationFact> relations,
        List<ObservationFact> observations,
        StatsMeta stats,
        List<String> warnings
) {
    public RootMergeResult {
        chunkResults = chunkResults == null ? List.of() : List.copyOf(chunkResults);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        relations = relations == null ? List.of() : List.copyOf(relations);
        observations = observations == null ? List.of() : List.copyOf(observations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
