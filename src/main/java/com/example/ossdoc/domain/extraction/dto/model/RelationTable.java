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
        @JsonProperty("contains")
        List<RelationFact> contains,

        @JsonProperty("extends")
        List<RelationFact> extendsRelations,

        @JsonProperty("implements")
        List<RelationFact> implementsRelations,

        @JsonProperty("overrides")
        List<RelationFact> overrides,

        @JsonProperty("calls")
        List<RelationFact> calls,

        @JsonProperty("accesses_field")
        List<RelationFact> accessesField,

        @JsonProperty("field_type")
        List<RelationFact> fieldType,

        @JsonProperty("param_type")
        List<RelationFact> paramType,

        @JsonProperty("return_type")
        List<RelationFact> returnType,

        @JsonProperty("throws_type")
        List<RelationFact> throwsType,

        @JsonProperty("annotated_by")
        List<RelationFact> annotatedBy
) {
}