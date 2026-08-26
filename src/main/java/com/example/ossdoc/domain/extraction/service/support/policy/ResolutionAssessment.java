package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.dto.model.RelationResolution;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;

import java.util.Objects;

/** 공통 Resolution 정책의 판정 결과. */
public record ResolutionAssessment(
        ResolutionStatus status,
        ResolutionBasis basis,
        String reason
) {
    public ResolutionAssessment {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        reason = normalize(reason);
    }

    public RelationResolution toRelationResolution() {
        return RelationResolution.builder()
                .status(status)
                .reason(reason)
                .build();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
