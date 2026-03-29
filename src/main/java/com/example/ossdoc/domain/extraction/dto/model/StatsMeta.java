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

        @JsonProperty("ast_files_scanned")
        long astFilesScanned,

        @JsonProperty("class_files_scanned")
        long classFilesScanned,

        @JsonProperty("ast_files_parsed")
        long astFilesParsed,

        @JsonProperty("class_files_parsed")
        long classFilesParsed,

        @JsonProperty("chunks_total")
        long chunksTotal,

        @JsonProperty("chunks_succeeded")
        long chunksSucceeded,

        @JsonProperty("chunks_failed")
        long chunksFailed,

        @JsonProperty("chunks_partial")
        long chunksPartial,

        @JsonProperty("types")
        long types,

        @JsonProperty("constructors")
        long constructors,

        @JsonProperty("methods")
        long methods,

        @JsonProperty("fields")
        long fields,

        @JsonProperty("edges_candidates")
        long edgeCandidates,

        @JsonProperty("relations")
        long relations,

        @JsonProperty("observations")
        long observations,

        @JsonProperty("evidence")
        long evidence,

        @JsonProperty("unresolved_type_refs")
        long unresolvedTypeRefs,

        @JsonProperty("errors")
        long errors
) {
}
