package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Graph Builder가 바로 사용할 수 있는 관계 후보 종류.
 */
public enum RelationKind {
    CALLS("calls"),
    CREATES("creates"),
    OVERRIDES("overrides"),
    ACCESSES_FIELD("accesses_field"),
    ANNOTATED_WITH("annotated_with"),
    HANDLES_ENDPOINT("handles_endpoint"),
    DECLARES_BEAN("declares_bean"),
    CONFIGURES_BEAN("configures_bean"),
    INJECTS("injects"),
    PUBLISHES_EVENT("publishes_event"),
    LISTENS_EVENT("listens_event"),
    PROVIDES_SPI("provides_spi"),
    LOADS_SERVICE("loads_service"),
    REFLECTS_TYPE("reflects_type"),
    REFLECTS_METHOD("reflects_method"),
    REFLECTS_FIELD("reflects_field"),
    REFLECTS_CONSTRUCTOR("reflects_constructor");

    private final String code;

    RelationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
