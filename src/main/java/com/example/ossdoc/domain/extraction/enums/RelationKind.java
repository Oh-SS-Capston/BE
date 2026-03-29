package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Graph Builder가 바로 먹을 수 있는 관계 후보 종류
 */
public enum RelationKind {
    CONTAINS("contains"),
    EXTENDS("extends"),
    IMPLEMENTS("implements"),
    OVERRIDES("overrides"),
    CALLS("calls"),
    ACCESSES_FIELD("accesses_field"),
    FIELD_TYPE("field_type"),
    PARAM_TYPE("param_type"),
    RETURN_TYPE("return_type"),
    THROWS_TYPE("throws_type"),
    ANNOTATED_BY("annotated_by");

    private final String code;

    RelationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}