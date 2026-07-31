package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 관계 후보를 종류별 bucket으로 나눈 테이블
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationTable(
        @JsonProperty("calls")
        List<RelationFact> calls,

        @JsonProperty("creates")
        List<RelationFact> creates,

        @JsonProperty("overrides")
        List<RelationFact> overrides,

        @JsonProperty("accesses_field")
        List<RelationFact> accessesField,

        @JsonProperty("annotated_with")
        List<RelationFact> annotatedWith
) {
}
