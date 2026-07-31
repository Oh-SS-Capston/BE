package com.example.ossdoc.domain.extraction.service.support.policy;

import com.fasterxml.jackson.annotation.JsonValue;

/** Graph 표시 정책에서 사용할 confidence 구간. */
public enum ConfidenceBand {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String code;

    ConfidenceBand(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
