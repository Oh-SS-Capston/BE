package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 자바 수정자
 */
public enum ModifierKind {
    STATIC("static"),
    FINAL("final"),
    ABSTRACT("abstract"),
    SYNCHRONIZED("synchronized"),
    NATIVE("native"),
    STRICTFP("strictfp"),
    TRANSIENT("transient"),
    VOLATILE("volatile"),
    DEFAULT("default"),
    SEALED("sealed"),
    NON_SEALED("non-sealed");

    private final String code;

    ModifierKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}