package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 저장소 유형 분류
 */
public enum RepoType {
    LIBRARY("library"),
    APPLICATION("application"),
    FRAMEWORK("framework"),
    PLUGIN("plugin"),
    UNKNOWN("unknown");

    private final String code;

    RepoType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
