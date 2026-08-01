package com.example.ossdoc.domain.extraction.service.support.policy;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 모든 의미 관계 Resolver가 공유하는 confidence 계산 정책. */
@Component
public class RelationConfidencePolicy {

    private static final double HIGH_THRESHOLD = 0.75;
    private static final double MEDIUM_THRESHOLD = 0.40;

    public ConfidenceAssessment assess(
            RelationPolicyInput input,
            ResolutionAssessment resolution
    ) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");

        double policyScore = baseScore(resolution.status());
        policyScore += originAdjustment(input.origin());
        policyScore += derivationAdjustment(input.derivation());
        policyScore += input.evidencePresent() ? 0.03 : -0.10;
        policyScore += input.qualifierMatched() ? 0.05 : 0.0;
        policyScore += input.primaryMatched() ? 0.03 : 0.0;
        policyScore += input.inferred() ? -0.12 : 0.0;
        policyScore -= ambiguityPenalty(input.candidateCount());

        double score = clamp(policyScore);
        if (input.sourceConfidenceHint() != null) {
            score = clamp(score * 0.75 + input.sourceConfidenceHint() * 0.25);
        }

        score = round(score);
        ConfidenceBand band = band(score);
        boolean defaultVisible = score >= HIGH_THRESHOLD
                && resolution.status() != ResolutionStatus.UNRESOLVED;

        return new ConfidenceAssessment(score, band, defaultVisible);
    }

    private double baseScore(ResolutionStatus status) {
        return switch (status) {
            case RESOLVED -> 0.90;
            case PARTIAL -> 0.55;
            case UNRESOLVED -> 0.25;
        };
    }

    private double originAdjustment(FactOriginKind origin) {
        if (origin == null) {
            return -0.05;
        }
        return switch (origin) {
            case AST_AND_BYTECODE -> 0.08;
            case RESOURCE -> 0.03;
            case AST, BYTECODE -> 0.0;
            case OBSERVED -> -0.05;
        };
    }

    private double derivationAdjustment(DerivationKind derivation) {
        if (derivation == null) {
            return 0.0;
        }
        return switch (derivation) {
            case DIRECT -> 0.03;
            case DERIVED -> 0.0;
            case INFERRED -> -0.08;
            case HEURISTIC -> -0.12;
        };
    }

    private double ambiguityPenalty(int candidateCount) {
        if (candidateCount <= 1) {
            return 0.0;
        }
        return Math.min(0.20, (candidateCount - 1) * 0.08);
    }

    private ConfidenceBand band(double score) {
        if (score >= HIGH_THRESHOLD) {
            return ConfidenceBand.HIGH;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return ConfidenceBand.MEDIUM;
        }
        return ConfidenceBand.LOW;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
