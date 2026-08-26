package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * facts.json 최상위 루트
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FactsDocument(
        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("job")
        JobMeta job,

        @JsonProperty("build")
        BuildMeta build,

        @JsonProperty("extraction")
        ExtractionMeta extraction,

        @JsonProperty("stats")
        StatsMeta stats,

        /**
         * evidence_id -> EvidenceFact (내부 Map 유지)
         */
        @JsonIgnore
        Map<String, EvidenceFact> evidenceMap,

        @JsonProperty("symbols")
        SymbolTable symbols,

        @JsonProperty("relations")
        RelationTable relations,

        @JsonProperty("observations")
        ObservationTable observations
) {

    /**
     * JSON 직렬화 시 evidence를 List로 출력
     */
    @JsonGetter("evidence")
    public List<EvidenceFact> evidence() {
        if (evidenceMap == null || evidenceMap.isEmpty()) {
            return List.of();
        }
        return evidenceMap.values().stream().toList();
    }
}
