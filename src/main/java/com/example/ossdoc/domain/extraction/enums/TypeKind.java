package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * TYPE 심볼의 세부 종류
 */
public enum TypeKind {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    RECORD("record"),
    ANNOTATION("annotation");

    private final String code;

    TypeKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}