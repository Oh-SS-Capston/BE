package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 소스 코드 위치(라인/컬럼 범위)
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceSpan(
        @JsonProperty("start_line")
        Integer startLine,

        @JsonProperty("start_col")
        Integer startCol,

        @JsonProperty("end_line")
        Integer endLine,

        @JsonProperty("end_col")
        Integer endCol
) {
}