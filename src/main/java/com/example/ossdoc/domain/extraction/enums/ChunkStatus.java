package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 청크 실행 상태
 */
public enum ChunkStatus {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    PARTIAL("partial");

    private final String code;

    ChunkStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}