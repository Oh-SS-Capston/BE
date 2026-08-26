package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * relation / observation 등의 출처
 */
public enum FactOriginKind {
    AST("ast"),
    BYTECODE("bytecode"),
    AST_AND_BYTECODE("ast_and_bytecode"),
    OBSERVED("observed"),
    RESOURCE("resource");

    private final String code;

    FactOriginKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}