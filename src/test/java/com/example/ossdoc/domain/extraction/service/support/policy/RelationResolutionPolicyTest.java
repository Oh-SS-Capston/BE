package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RelationResolutionPolicyTest {

    private final RelationResolutionPolicy policy =
            new RelationResolutionPolicy();

    @Test
    @DisplayName("유일한 명시적 symbol 대상은 RESOLVED로 판정한다")
    void resolvesExactSymbol() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetSymbolResolved(true)
                        .candidateCount(1)
                        .build()
        );

        assertEquals(ResolutionStatus.RESOLVED, result.status());
        assertEquals(ResolutionBasis.EXACT_SYMBOL, result.basis());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("Endpoint·Bean 이름 같은 확정 reference도 RESOLVED로 판정한다")
    void resolvesAuthoritativeReference() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetReferenceKnown(true)
                        .targetReferenceAuthoritative(true)
                        .candidateCount(1)
                        .build()
        );

        assertEquals(ResolutionStatus.RESOLVED, result.status());
        assertEquals(ResolutionBasis.EXACT_REFERENCE, result.basis());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("추론으로 선택된 symbol은 PARTIAL로 판정한다")
    void marksInferredSymbolAsPartial() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetSymbolResolved(true)
                        .inferred(true)
                        .candidateCount(1)
                        .build()
        );

        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertEquals(ResolutionBasis.INFERRED_SYMBOL, result.basis());
    }

    @Test
    @DisplayName("추론으로 만든 Bean reference는 PARTIAL로 판정한다")
    void marksInferredReferenceAsPartial() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetReferenceKnown(true)
                        .targetReferenceAuthoritative(true)
                        .inferred(true)
                        .candidateCount(1)
                        .build()
        );

        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertEquals(ResolutionBasis.INFERRED_REFERENCE, result.basis());
    }

    @Test
    @DisplayName("후보가 여러 개면 대상 값이 있어도 PARTIAL ambiguous로 판정한다")
    void marksMultipleCandidatesAsAmbiguous() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetReferenceKnown(true)
                        .targetReferenceAuthoritative(true)
                        .candidateCount(3)
                        .build()
        );

        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertEquals(ResolutionBasis.AMBIGUOUS_CANDIDATES, result.basis());
    }

    @Test
    @DisplayName("raw reference만 확인되면 PARTIAL로 판정한다")
    void marksRawReferenceAsPartial() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder()
                        .targetReferenceKnown(true)
                        .build()
        );

        assertEquals(ResolutionStatus.PARTIAL, result.status());
        assertEquals(ResolutionBasis.RAW_REFERENCE, result.basis());
    }

    @Test
    @DisplayName("대상 정보가 전혀 없으면 UNRESOLVED로 판정한다")
    void marksUnknownTargetAsUnresolved() {
        ResolutionAssessment result = policy.assess(
                RelationPolicyInput.builder().build()
        );

        assertEquals(ResolutionStatus.UNRESOLVED, result.status());
        assertEquals(ResolutionBasis.UNKNOWN_TARGET, result.basis());
    }
}
