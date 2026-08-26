package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * facts 추출 통계
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatsMeta(
        @JsonProperty("files_scanned")
        long filesScanned,

        @JsonProperty("files_parsed")
        long filesParsed,

        @JsonProperty("files_skipped")
        long filesSkipped,

        @JsonProperty("types")
        long types,

        @JsonProperty("constructors")
        long constructors,

        @JsonProperty("methods")
        long methods,

        @JsonProperty("fields")
        long fields,

        @JsonProperty("relations")
        long relations,

        @JsonProperty("observations")
        long observations,

        @JsonProperty("evidence")
        long evidence,

        @JsonProperty("unresolved_type_ratio")
        double unresolvedTypeRatio,

        @JsonProperty("unresolved_type_refs_total")
        long unresolvedTypeRefsTotal,

        @JsonProperty("total_type_refs_total")
        long totalTypeRefsTotal,

        @JsonProperty("errors")
        long errors
) {
}
