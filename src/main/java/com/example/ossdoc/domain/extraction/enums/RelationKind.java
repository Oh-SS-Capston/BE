package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Graph Builder가 바로 먹을 수 있는 관계 후보 종류
 */
public enum RelationKind {
    CALLS("calls"),
    CREATES("creates"),
    OVERRIDES("overrides"),
    ACCESSES_FIELD("accesses_field");

    private final String code;

    RelationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
