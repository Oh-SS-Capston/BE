package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class FactsEdgeConverter {

    private final ObjectMapper objectMapper;

    public FactsEdgeConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Edge toEntity(
            RepoRun run,
            NormalizedRelationFact dto,
            SymbolEntity fromSymbol,
            SymbolEntity toSymbol
    ) {
        JsonNode toRawRef = resolveToRawRef(dto, toSymbol);
        JsonNode attrs = resolveAttrs(dto.attrs());

        ResolutionStatus resolutionStatus =
                resolveResolution(dto, toSymbol);

        return new Edge(
                null,
                run,
                toEdgeType(dto.kind()),
                fromSymbol,
                toSymbol,
                toRawRef,
                toOriginKind(dto.origin()),
                toDerivationKind(dto.derivation()),
                resolutionStatus,
                dto.resolutionReason(),
                dto.callSiteLine(),
                dto.confidenceHint(),
                attrs
        );
    }


    private JsonNode resolveToRawRef(
            NormalizedRelationFact dto,
            SymbolEntity toSymbol
    ) {
        if (toSymbol != null) {
            return null;
        }

        if (dto.dstRawRef() != null
                && !dto.dstRawRef().isBlank()) {
            var node = JsonNodeFactory.instance.objectNode();

            node.put("raw", dto.dstRawRef());
            node.put("unresolved", true);

            return node;
        }

        if (dto.dstSymbol() != null
                && !dto.dstSymbol().isBlank()) {
            var node = JsonNodeFactory.instance.objectNode();

            node.put("raw", dto.dstSymbol());
            node.put("unresolved", true);

            return node;
        }

        return null;
    }

    private ResolutionStatus resolveResolution(
            NormalizedRelationFact dto,
            SymbolEntity toSymbol
    ) {
        String value = dto.resolutionStatus();

        if (value == null || value.isBlank()) {
            return toSymbol == null
                    ? ResolutionStatus.UNRESOLVED
                    : ResolutionStatus.RESOLVED;
        }

        return switch (normalizeEnumValue(value)) {
            case "RESOLVED" -> ResolutionStatus.RESOLVED;
            case "PARTIAL" -> ResolutionStatus.PARTIAL;
            case "UNRESOLVED" -> ResolutionStatus.UNRESOLVED;

            default -> throw new IllegalArgumentException(
                    "Unsupported resolution status: " + value
            );
        };
    }

    private EdgeType toEdgeType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Relation kind must not be null or blank."
            );
        }

        return switch (normalizeEnumValue(value)) {
            case "CONTAINS" -> EdgeType.CONTAINS;
            case "EXTENDS" -> EdgeType.EXTENDS;
            case "IMPLEMENTS" -> EdgeType.IMPLEMENTS;
            case "OVERRIDES" -> EdgeType.OVERRIDES;
            case "CALLS" -> EdgeType.CALLS;
            case "CREATES" -> EdgeType.CREATES;
            case "ACCESSES_FIELD" -> EdgeType.ACCESSES_FIELD;
            case "HAS_FIELD", "FIELD_TYPE" -> EdgeType.HAS_FIELD;
            case "PARAM", "PARAM_TYPE" -> EdgeType.PARAM;
            case "RETURNS", "RETURN_TYPE" -> EdgeType.RETURNS;
            case "THROWS", "THROWS_TYPE" -> EdgeType.THROWS;
            case "ANNOTATED_WITH", "ANNOTATED_BY" -> EdgeType.ANNOTATED_WITH;

            case "HANDLES_ENDPOINT" -> EdgeType.HANDLES_ENDPOINT;
            case "DECLARES_BEAN" -> EdgeType.DECLARES_BEAN;
            case "CONFIGURES_BEAN" -> EdgeType.CONFIGURES_BEAN;
            case "INJECTS" -> EdgeType.INJECTS;
            case "PUBLISHES_EVENT" -> EdgeType.PUBLISHES_EVENT;
            case "LISTENS_EVENT" -> EdgeType.LISTENS_EVENT;
            case "PROVIDES_SPI" -> EdgeType.PROVIDES_SPI;
            case "LOADS_SERVICE" -> EdgeType.LOADS_SERVICE;
            case "REFLECTS_TYPE" -> EdgeType.REFLECTS_TYPE;
            case "REFLECTS_METHOD" -> EdgeType.REFLECTS_METHOD;
            case "REFLECTS_FIELD" -> EdgeType.REFLECTS_FIELD;
            case "REFLECTS_CONSTRUCTOR" -> EdgeType.REFLECTS_CONSTRUCTOR;

            default -> throw new IllegalArgumentException(
                    "Unsupported relation kind: " + value
            );
        };
    }

    private OriginKind toOriginKind(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Relation origin must not be null or blank."
            );
        }

        return switch (normalizeEnumValue(value)) {
            case "AST" -> OriginKind.AST;
            case "BYTECODE" -> OriginKind.BYTECODE;
            case "AST_AND_BYTECODE", "MERGED" -> OriginKind.MERGED;
            case "CONTRACT" -> OriginKind.CONTRACT;
            case "DERIVED" -> OriginKind.DERIVED;
            case "OBSERVED" -> OriginKind.OBSERVED;
            case "RESOURCE" -> OriginKind.RESOURCE;

            default -> throw new IllegalArgumentException(
                    "Unsupported relation origin: " + value
            );
        };
    }

    private DerivationKind toDerivationKind(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Relation derivation must not be null or blank."
            );
        }

        return switch (normalizeEnumValue(value)) {
            case "DIRECT" -> DerivationKind.DIRECT;
            case "DERIVED" -> DerivationKind.DERIVED;
            case "INFERRED" -> DerivationKind.INFERRED;
            case "HEURISTIC" -> DerivationKind.HEURISTIC;

            default -> throw new IllegalArgumentException(
                    "Unsupported relation derivation: " + value
            );
        };
    }

    private JsonNode resolveAttrs(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }

        return objectMapper.valueToTree(attrs);
    }

    private String normalizeEnumValue(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
