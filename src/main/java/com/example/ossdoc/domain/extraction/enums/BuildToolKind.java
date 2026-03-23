package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 빌드 도구 종류
 * BuildStatus, BuildToolKind는 extraction에서 새로 만들지 말고,
 * 지금은 String 유지 후 build 도메인 enum이 확정되면 그걸 참조하거나 매핑하자.
 */
public enum BuildToolKind {
    GRADLE("gradle"),
    MAVEN("maven"),
    ANT("ant"),
    BAZEL("bazel"),
    UNKNOWN("unknown");

    private final String code;

    BuildToolKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}