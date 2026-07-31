package com.example.ossdoc.domain.extraction.service.support.policy;

import com.fasterxml.jackson.annotation.JsonValue;

/** ResolutionStatus를 결정한 공통 근거 분류. */
public enum ResolutionBasis {
    EXACT_SYMBOL("exact_symbol"),
    EXACT_REFERENCE("exact_reference"),
    INFERRED_SYMBOL("inferred_symbol"),
    INFERRED_REFERENCE("inferred_reference"),
    AMBIGUOUS_CANDIDATES("ambiguous_candidates"),
    RAW_REFERENCE("raw_reference"),
    UNKNOWN_TARGET("unknown_target");

    private final String code;

    ResolutionBasis(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
