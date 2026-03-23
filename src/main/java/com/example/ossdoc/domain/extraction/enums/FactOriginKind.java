package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * relation / observation 등의 출처
 */
public enum FactOriginKind {
    AST("AST"),
    BYTECODE("BYTECODE"),
    MERGED("MERGED"),
    OBSERVED("OBSERVED"),
    RESOURCE("RESOURCE");

    private final String code;

    FactOriginKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}