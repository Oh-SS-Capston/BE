package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 심볼을 종류별 bucket으로 나눈 테이블
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SymbolTable(
        @JsonProperty("types")
        List<SymbolFact> types,

        @JsonProperty("constructors")
        List<SymbolFact> constructors,

        @JsonProperty("methods")
        List<SymbolFact> methods,

        @JsonProperty("fields")
        List<SymbolFact> fields
) {
}
