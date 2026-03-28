package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * facts 추출 모드
 */
public enum ExtractionMode {
    AST_ONLY("ast-only"),
    AST_PLUS_BYTECODE("ast+bytecode"),
    AST_PLUS_PARTIAL_BYTECODE("ast+partial-bytecode");

    private final String code;

    ExtractionMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}