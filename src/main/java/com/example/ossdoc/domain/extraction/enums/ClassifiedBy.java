package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 분류 수행 주체
 */
public enum ClassifiedBy {
    HEURISTIC("heuristic"),
    USER("user"),
    LLM("llm");

    private final String code;

    ClassifiedBy(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
