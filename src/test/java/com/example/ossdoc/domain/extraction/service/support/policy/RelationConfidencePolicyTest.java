package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationConfidencePolicyTest {

    private final RelationResolutionPolicy resolutionPolicy =
            new RelationResolutionPolicy();
    private final RelationConfidencePolicy confidencePolicy =
            new RelationConfidencePolicy();

    @Test
    @DisplayName("AST와 BYTECODE가 함께 확인한 resolved 관계는 HIGH로 계산한다")
    void scoresResolvedAstAndBytecodeAsHigh() {
        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(true)
                .candidateCount(1)
                .evidencePresent(true)
                .sourceConfidenceHint(0.9)
                .build();

        ConfidenceAssessment result = confidencePolicy.assess(
                input,
                resolutionPolicy.assess(input)
        );

        assertEquals(0.975, result.value());
        assertEquals(ConfidenceBand.HIGH, result.band());
        assertTrue(result.defaultVisible());
    }

    @Test
    @DisplayName("다중 후보 관계는 confidence를 낮추고 기본 표시에서 제외한다")
    void penalizesAmbiguousCandidates() {
        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(FactOriginKind.AST)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(true)
                .candidateCount(3)
                .evidencePresent(true)
                .sourceConfidenceHint(0.6)
                .build();

        ConfidenceAssessment result = confidencePolicy.assess(
                input,
                resolutionPolicy.assess(input)
        );

        assertEquals(0.465, result.value());
        assertEquals(ConfidenceBand.MEDIUM, result.band());
        assertFalse(result.defaultVisible());
    }

    @Test
    @DisplayName("근거가 없고 대상도 모르는 관계는 LOW로 계산한다")
    void scoresUnknownTargetWithoutEvidenceAsLow() {
        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(FactOriginKind.OBSERVED)
                .derivation(DerivationKind.HEURISTIC)
                .evidencePresent(false)
                .sourceConfidenceHint(0.2)
                .build();

        ConfidenceAssessment result = confidencePolicy.assess(
                input,
                resolutionPolicy.assess(input)
        );

        assertEquals(0.05, result.value());
        assertEquals(ConfidenceBand.LOW, result.band());
        assertFalse(result.defaultVisible());
    }

    @Test
    @DisplayName("Qualifier와 Primary 일치는 resolved 관계의 confidence를 높인다")
    void rewardsExplicitDiMatches() {
        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(FactOriginKind.AST)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(true)
                .candidateCount(1)
                .qualifierMatched(true)
                .primaryMatched(true)
                .evidencePresent(true)
                .build();

        ConfidenceAssessment result = confidencePolicy.assess(
                input,
                resolutionPolicy.assess(input)
        );

        assertEquals(1.0, result.value());
        assertEquals(ConfidenceBand.HIGH, result.band());
        assertTrue(result.defaultVisible());
    }
}
