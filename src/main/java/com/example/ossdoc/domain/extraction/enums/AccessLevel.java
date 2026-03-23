package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 접근 제어자
 */
public enum AccessLevel {
    PUBLIC("public"),
    PROTECTED("protected"),
    PACKAGE("package"),
    PRIVATE("private");

    private final String code;

    AccessLevel(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}