package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * evidence 종류
 */
public enum EvidenceType {
    AST("ast"),
    BYTECODE("bytecode"),
    RESOURCE("resource"),
    README("readme"),
    TEST("test");

    private final String code;

    EvidenceType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
