package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 모든 의미 관계 Resolver가 공유하는 Resolution 판정 정책. */
@Component
public class RelationResolutionPolicy {

    public ResolutionAssessment assess(RelationPolicyInput input) {
        Objects.requireNonNull(input, "input must not be null");

        if (input.candidateCount() > 1) {
            return new ResolutionAssessment(
                    ResolutionStatus.PARTIAL,
                    ResolutionBasis.AMBIGUOUS_CANDIDATES,
                    "Multiple candidates matched the relation target"
            );
        }

        if (input.targetSymbolResolved() && !input.inferred()) {
            return new ResolutionAssessment(
                    ResolutionStatus.RESOLVED,
                    ResolutionBasis.EXACT_SYMBOL,
                    null
            );
        }

        if (input.targetReferenceAuthoritative() && !input.inferred()) {
            return new ResolutionAssessment(
                    ResolutionStatus.RESOLVED,
                    ResolutionBasis.EXACT_REFERENCE,
                    null
            );
        }

        if (input.targetSymbolResolved()) {
            return new ResolutionAssessment(
                    ResolutionStatus.PARTIAL,
                    ResolutionBasis.INFERRED_SYMBOL,
                    "Target symbol was selected by inference"
            );
        }

        if (input.targetReferenceAuthoritative()) {
            return new ResolutionAssessment(
                    ResolutionStatus.PARTIAL,
                    ResolutionBasis.INFERRED_REFERENCE,
                    "Target reference was determined by inference"
            );
        }

        if (input.targetReferenceKnown()) {
            return new ResolutionAssessment(
                    ResolutionStatus.PARTIAL,
                    ResolutionBasis.RAW_REFERENCE,
                    "Target reference is known but is not fully resolved"
            );
        }

        return new ResolutionAssessment(
                ResolutionStatus.UNRESOLVED,
                ResolutionBasis.UNKNOWN_TARGET,
                "Relation target could not be determined"
        );
    }
}
