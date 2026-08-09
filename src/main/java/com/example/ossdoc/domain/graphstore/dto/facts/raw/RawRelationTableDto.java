package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawRelationTableDto {

    private List<RawRelationFactDto> contains = new ArrayList<>();

    @JsonProperty("extends")
    private List<RawRelationFactDto> extendsRelations = new ArrayList<>();

    @JsonProperty("implements")
    private List<RawRelationFactDto> implementsRelations = new ArrayList<>();

    private List<RawRelationFactDto> overrides = new ArrayList<>();
    private List<RawRelationFactDto> calls = new ArrayList<>();
    private List<RawRelationFactDto> creates = new ArrayList<>();

    @JsonProperty("accesses_field")
    private List<RawRelationFactDto> accessesField = new ArrayList<>();

    @JsonProperty("field_type")
    private List<RawRelationFactDto> fieldType = new ArrayList<>();

    @JsonProperty("param_type")
    private List<RawRelationFactDto> paramType = new ArrayList<>();

    @JsonProperty("return_type")
    private List<RawRelationFactDto> returnType = new ArrayList<>();

    @JsonProperty("throws_type")
    private List<RawRelationFactDto> throwsType = new ArrayList<>();

    @JsonProperty("annotated_with")
    private List<RawRelationFactDto> annotatedWith = new ArrayList<>();

    @JsonProperty("annotated_by")
    private List<RawRelationFactDto> annotatedBy = new ArrayList<>();
}