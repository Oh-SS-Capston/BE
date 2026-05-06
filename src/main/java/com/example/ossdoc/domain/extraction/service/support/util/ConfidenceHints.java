package com.example.ossdoc.domain.extraction.service.support.util;

import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;

import java.util.List;

public final class ConfidenceHints {
    private ConfidenceHints() {}

    public static double relation(ResolutionStatus status, FactOriginKind origin) {
        if (status == null) return 0.3;
        return switch (status) {
            case RESOLVED -> origin == FactOriginKind.BYTECODE ? 0.85 : 0.9;
            case PARTIAL  -> 0.6;
            case UNRESOLVED -> 0.3;
        };
    }

    /**
     * refactplan §12-1 기준:
     * - evidence 없음 → 0.3
     * - evidence 중 RESOURCE 존재 → 0.6
     * - evidence 2개 이상 (AST/BYTECODE) → 0.9
     * - evidence 1개 (AST/BYTECODE) → 0.7
     */
    public static double observation(List<EvidenceType> evidenceTypes) {
        if (evidenceTypes == null || evidenceTypes.isEmpty()) return 0.3;
        if (evidenceTypes.stream().anyMatch(t -> t == EvidenceType.RESOURCE)) return 0.6;
        return evidenceTypes.size() >= 2 ? 0.9 : 0.7;
    }
}
