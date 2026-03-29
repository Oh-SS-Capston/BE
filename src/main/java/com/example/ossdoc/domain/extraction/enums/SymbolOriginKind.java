package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * symbol 자체의 생성 출처
 */
public enum SymbolOriginKind {
    SOURCE("source"),
    BYTECODE("bytecode"),
    GENERATED("generated"),
    MERGED("merged");

    private final String code;

    SymbolOriginKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}