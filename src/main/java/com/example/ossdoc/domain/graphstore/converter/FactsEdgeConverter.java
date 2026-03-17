package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.dto.facts.RelationFactDto;
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

    public Edge toEntity(RepoRun run, RelationFactDto dto, SymbolEntity fromSymbol, SymbolEntity toSymbol) {
        JsonNode toRawRef = resolveToRawRef(dto, toSymbol);
        ResolutionStatus resolutionStatus = resolveResolution(dto, toSymbol);

        return new Edge(
                null,
                run,
                toEdgeType(dto.getKind()),
                fromSymbol,
                toSymbol,
                toRawRef,
                toOriginKind(dto.getOrigin()),
                resolutionStatus,
                dto.getConfidenceHint()
        );
    }

    private JsonNode resolveToRawRef(RelationFactDto dto, SymbolEntity toSymbol) {
        if (toSymbol != null) return null;
        if (dto.getDstTypeRef() != null && !dto.getDstTypeRef().isNull()) {
            return dto.getDstTypeRef();
        }
        if (dto.getDstSymbol() != null && !dto.getDstSymbol().isBlank()) {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("raw", dto.getDstSymbol());
            node.put("unresolved", true);
            return node;
        }
        return null;
    }

    private ResolutionStatus resolveResolution(RelationFactDto dto, SymbolEntity toSymbol) {
        if (dto.getResolution() != null) {
            return switch (dto.getResolution().trim().toUpperCase()) {
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
            case "HAS_FIELD", "FIELD_TYPE" -> EdgeType.HAS_FIELD;
            case "PARAM", "PARAM_TYPE" -> EdgeType.PARAM;
            case "RETURNS", "RETURN_TYPE" -> EdgeType.RETURNS;
            case "THROWS", "THROWS_TYPE" -> EdgeType.THROWS;
            case "ANNOTATED_WITH" -> EdgeType.ANNOTATED_WITH;
            default -> EdgeType.CALLS;
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