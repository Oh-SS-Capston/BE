package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 참조 해석 상태
 */
public enum ResolutionStatus {
    RESOLVED("resolved"),
    PARTIAL("partial"),
    UNRESOLVED("unresolved");

    private final String code;

    ResolutionStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}