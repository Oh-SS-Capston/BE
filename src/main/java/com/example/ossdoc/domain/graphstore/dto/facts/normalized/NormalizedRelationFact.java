package com.example.ossdoc.domain.graphstore.dto.facts.normalized;

import java.math.BigDecimal;
import java.util.List;

public record NormalizedRelationFact(
        String kind,
        String srcSymbol,
        String dstSymbol,
        String dstRawRef,
        String origin,
        String resolutionStatus,
        String resolutionReason,
        BigDecimal confidenceHint,
        List<String> evidenceIds
) {
}