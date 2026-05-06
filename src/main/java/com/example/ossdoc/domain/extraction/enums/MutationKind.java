package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MutationKind {
    FIELD_WRITE("field_write"),
    CALL_MUTATING("call_mutating");

    private final String code;
    MutationKind(String code) { this.code = code; }

    @JsonValue
    public String code() { return code; }
}
