package com.example.ossdoc.domain.graphstore.model.normalized;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record NormalizedSymbolFact(
        String symbol,
        String name,
        String kind,
        String typeKind,
        String sourceRoot,
        String access,
        List<String> modifiers,
        String origin,
        String qualifiedName,
        String ownerTypeSymbol,
        String packageSymbol,
        String module,
        String sourceFile,
        List<String> evidenceIds,
        JsonNode signature,
        String superclassTypeRef,
        List<String> interfaceTypeRefs,
        String returnTypeRef,
        List<String> paramTypeRefs,
        List<String> throwsTypeRefs,
        String fieldTypeRef,
        String docComment,
        JsonNode annotations
) {
}