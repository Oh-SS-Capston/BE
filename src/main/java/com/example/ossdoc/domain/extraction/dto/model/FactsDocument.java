package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

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
         * evidence_id -> EvidenceFact
         */
        @JsonProperty("evidence")
        Map<String, EvidenceFact> evidence,

        @JsonProperty("symbols")
        SymbolTable symbols,

        @JsonProperty("relations")
        RelationTable relations,

        @JsonProperty("observations")
        ObservationTable observations
) {
}