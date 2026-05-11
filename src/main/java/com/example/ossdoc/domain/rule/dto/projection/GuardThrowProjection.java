package com.example.ossdoc.domain.rule.dto.projection;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;

public record GuardThrowProjection(
        SymbolEntity methodSymbol,
        RuleMiningSignal conditionSignal,
        RuleMiningSignal throwSignal,
        Evidence conditionEvidence,
        Evidence throwEvidence,
        int lineDistance
) {
}