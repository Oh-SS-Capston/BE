package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 심볼의 최상위 분류
 * 세부 타입 종류(class/interface/enum/record/annotation)는 TypeKind로 분리
 */
public enum SymbolFactKind {
    MODULE("module"),
    PACKAGE("package"),
    TYPE("type"),
    METHOD("method"),
    FIELD("field"),
    CONSTRUCTOR("constructor");

    private final String code;

    SymbolFactKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}