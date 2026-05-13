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

    /**
     * facts.json 출력용 2값 매핑.
     * 내부 3값을 외부 2값(ast_only, ast_and_bytecode)으로 변환한다.
     */
    public String outputCode() {
        return switch (this) {
            case AST_ONLY -> "ast_only";
            case AST_PLUS_BYTECODE, AST_PLUS_PARTIAL_BYTECODE -> "ast_and_bytecode";
        };
    }
}