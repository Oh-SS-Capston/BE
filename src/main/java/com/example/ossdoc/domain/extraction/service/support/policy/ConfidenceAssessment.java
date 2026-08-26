package com.example.ossdoc.domain.extraction.service.support.policy;

/** 공통 Confidence 정책의 계산 결과. */
public record ConfidenceAssessment(
        double value,
        ConfidenceBand band,
        boolean defaultVisible
) {
}
