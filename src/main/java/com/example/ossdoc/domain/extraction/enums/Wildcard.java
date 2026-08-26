package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 와일드카드 타입 종류
 */
public enum Wildcard {
    UNBOUNDED("unbounded"),
    EXTENDS("extends"),
    SUPER("super");

    private final String code;

    Wildcard(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
