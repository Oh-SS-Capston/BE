package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

@Component
public class FactsEdgeConverter {

    public Edge toEntity(RepoRun run, NormalizedRelationFact dto, SymbolEntity fromSymbol, SymbolEntity toSymbol) {
        JsonNode toRawRef = resolveToRawRef(dto, toSymbol);
        ResolutionStatus resolutionStatus = resolveResolution(dto, toSymbol);

        return new Edge(
                null,
                run,
                toEdgeType(dto.kind()),
                fromSymbol,
                toSymbol,
                toRawRef,
                toOriginKind(dto.origin()),
                resolutionStatus,
                dto.confidenceHint()
        );
    }

    private JsonNode resolveToRawRef(NormalizedRelationFact dto, SymbolEntity toSymbol) {
        if (toSymbol != null) {
            return null;
        }

        if (dto.dstRawRef() != null && !dto.dstRawRef().isBlank()) {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("raw", dto.dstRawRef());
            node.put("unresolved", true);
            return node;
        }

        if (dto.dstSymbol() != null && !dto.dstSymbol().isBlank()) {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("raw", dto.dstSymbol());
            node.put("unresolved", true);
            return node;
        }

        return null;
    }

    private ResolutionStatus resolveResolution(NormalizedRelationFact dto, SymbolEntity toSymbol) {
        if (dto.resolutionStatus() != null) {
            return switch (dto.resolutionStatus().trim().toUpperCase()) {
                case "PARTIAL" -> ResolutionStatus.PARTIAL;
                case "UNRESOLVED" -> ResolutionStatus.UNRESOLVED;
                default -> ResolutionStatus.RESOLVED;
            };
        }
        return toSymbol == null ? ResolutionStatus.UNRESOLVED : ResolutionStatus.RESOLVED;
    }

    private EdgeType toEdgeType(String value) {
        if (value == null) return EdgeType.CALLS;
        return switch (value.trim().toUpperCase()) {
            case "CONTAINS" -> EdgeType.CONTAINS;
            case "EXTENDS" -> EdgeType.EXTENDS;
            case "IMPLEMENTS" -> EdgeType.IMPLEMENTS;
            case "OVERRIDES" -> EdgeType.OVERRIDES;
            case "ACCESSES_FIELD" -> EdgeType.ACCESSES_FIELD;
            case "HAS_FIELD", "FIELD_TYPE" -> EdgeType.HAS_FIELD;
            case "PARAM", "PARAM_TYPE" -> EdgeType.PARAM;
            case "RETURNS", "RETURN_TYPE" -> EdgeType.RETURNS;
            case "THROWS", "THROWS_TYPE" -> EdgeType.THROWS;
            case "ANNOTATED_WITH", "ANNOTATED_BY" -> EdgeType.ANNOTATED_WITH;
            default -> EdgeType.CALLS;
        };
    }

    private OriginKind toOriginKind(String value) {
        if (value == null) return OriginKind.AST;
        return switch (value.trim().toUpperCase()) {
            case "BYTECODE" -> OriginKind.BYTECODE;
            case "MERGED" -> OriginKind.MERGED;
            case "CONTRACT" -> OriginKind.CONTRACT;
            case "DERIVED" -> OriginKind.DERIVED;
            default -> OriginKind.AST;
        };
    }
}