package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * extraction 시작 전 bytecode 사용 가능 상태
 */
public enum BytecodeAvailability {
    FULL("full"),
    PARTIAL("partial"),
    NONE("none");

    private final String code;

    BytecodeAvailability(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}