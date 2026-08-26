package com.example.ossdoc.domain.rule.dto.projection;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;

public record PersistenceActionProjection(
        SymbolEntity methodSymbol,
        RuleMiningSignal actionSignal,
        RuleMiningSignalType actionType,
        Edge edge,
        Evidence evidence,
        String actionText
) {
}