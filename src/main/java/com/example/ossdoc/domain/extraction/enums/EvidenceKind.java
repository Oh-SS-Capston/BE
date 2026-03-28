package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * evidence 종류
 */
public enum EvidenceKind {
    AST("AST"),
    BYTECODE("BYTECODE"),
    RESOURCE("RESOURCE"),
    README("README"),
    TEST("TEST");

    private final String code;

    EvidenceKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}