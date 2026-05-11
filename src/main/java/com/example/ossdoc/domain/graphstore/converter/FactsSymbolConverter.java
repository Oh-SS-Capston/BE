package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

@Component
public class FactsSymbolConverter {

    public SymbolEntity toEntity(String symbolId, RepoRun run, NormalizedSymbolFact dto) {
        JsonNode modifiers = buildModifiers(dto);
        JsonNode signature = buildSignature(dto);

        return new SymbolEntity(
                symbolId,
                run,
                null, // module 미연동
                toSymbolKind(dto.kind()),
                dto.symbol(),
                resolveSimpleName(dto),
                toAccessLevel(dto.access()),
                modifiers,
                null, // owner는 2차 연결
                signature,
                null, // sourceFile은 FileIndex 타입이라 현재는 null
                null,
                null,
                toOriginKind(dto.origin())
        );
    }

    private JsonNode buildModifiers(NormalizedSymbolFact dto) {
        var array = JsonNodeFactory.instance.arrayNode();
        if (dto.modifiers() != null) {
            dto.modifiers().forEach(array::add);
        }
        return array;
    }

    private JsonNode buildSignature(NormalizedSymbolFact dto) {
        if (dto.signature() != null && !dto.signature().isNull()) {
            return dto.signature();
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private String resolveSimpleName(NormalizedSymbolFact dto) {
        if (dto.name() != null && !dto.name().isBlank()) {
            return dto.name();
        }
        String symbol = dto.symbol();
        if (symbol == null || symbol.isBlank()) return null;
        int idx = Math.max(symbol.lastIndexOf('.'), symbol.lastIndexOf('#'));
        return idx >= 0 ? symbol.substring(idx + 1) : symbol;
    }

    private SymbolKind toSymbolKind(String value) {
        if (value == null) return SymbolKind.TYPE;
        return switch (value.trim().toUpperCase()) {
            case "MODULE" -> SymbolKind.MODULE;
            case "PACKAGE" -> SymbolKind.PACKAGE;
            case "CONSTRUCTOR" -> SymbolKind.CONSTRUCTOR;
            case "METHOD" -> SymbolKind.METHOD;
            case "FIELD" -> SymbolKind.FIELD;
            default -> SymbolKind.TYPE;
        };
    }

    private AccessLevel toAccessLevel(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase()) {
            case "public" -> AccessLevel.PUBLIC;
            case "protected" -> AccessLevel.PROTECTED;
            case "package" -> AccessLevel.PACKAGE;
            case "private" -> AccessLevel.PRIVATE;
            default -> null;
        };
    }

    private OriginKind toOriginKind(String value) {
        if (value == null) return OriginKind.AST;
        return switch (value.trim().toUpperCase()) {
            case "BYTECODE" -> OriginKind.BYTECODE;
            case "MERGED" -> OriginKind.MERGED;
            case "CONTRACT" -> OriginKind.CONTRACT;
            default -> OriginKind.AST;
        };
    }
}