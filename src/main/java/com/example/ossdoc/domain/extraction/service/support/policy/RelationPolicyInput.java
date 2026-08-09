package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import lombok.Builder;

/**
 * 의미 관계 Resolver가 공통 Resolution·Confidence 정책에 전달하는 입력.
 *
 * <p>{@code targetReferenceAuthoritative}는 HTTP 경로, Bean 이름,
 * component-scan 패키지처럼 Symbol은 아니지만 관계의 목적지로서 완전히
 * 확정된 정규화 reference를 뜻한다.</p>
 */
@Builder
public record RelationPolicyInput(
        FactOriginKind origin,
        DerivationKind derivation,
        boolean targetSymbolResolved,
        boolean targetReferenceKnown,
        boolean targetReferenceAuthoritative,
        boolean inferred,
        int candidateCount,
        boolean qualifierMatched,
        boolean primaryMatched,
        boolean evidencePresent,
        Double sourceConfidenceHint
) {
    public RelationPolicyInput {
        candidateCount = Math.max(0, candidateCount);
        sourceConfidenceHint = clampNullable(sourceConfidenceHint);
    }

    private static Double clampNullable(Double value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
