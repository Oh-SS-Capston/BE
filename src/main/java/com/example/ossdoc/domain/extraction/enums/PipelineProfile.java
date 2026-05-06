package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 파이프라인 프로파일
 */
public enum PipelineProfile {
    FULL("full"),
    LIBRARY("library"),
    MINIMAL("minimal");

    private final String code;

    PipelineProfile(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
