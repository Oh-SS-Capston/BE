package com.example.ossdoc.domain.rule.dto.projection;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;

public record GuardReturnProjection(
        SymbolEntity methodSymbol,
        RuleMiningSignal conditionSignal,
        RuleMiningSignal returnSignal,
        RuleMiningSignal errorResponseSignal,
        Evidence conditionEvidence,
        Evidence returnEvidence,
        Evidence errorResponseEvidence,
        int lineDistance
) {
}