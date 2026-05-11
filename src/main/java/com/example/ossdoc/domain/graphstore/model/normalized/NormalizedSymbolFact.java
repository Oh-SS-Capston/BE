package com.example.ossdoc.domain.graphstore.model.normalized;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record NormalizedSymbolFact(
        String symbol,
        String name,
        String kind,
        String access,
        List<String> modifiers,
        String origin,
        String qualifiedName,
        String ownerTypeSymbol,
        String packageSymbol,
        String module,
        String sourceFile,
        List<String> evidenceIds,
        JsonNode signature
) {
}