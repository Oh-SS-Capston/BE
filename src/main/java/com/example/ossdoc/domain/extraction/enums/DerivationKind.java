package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DerivationKind {
    DIRECT("direct"),
    DERIVED("derived"),
    INFERRED("inferred"),
    HEURISTIC("heuristic");

    private final String code;

    DerivationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}