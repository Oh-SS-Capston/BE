package com.example.ossdoc.domain.graphstore.model.projection;

import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * GraphStore ingest 중 기존 edge 중복 판별에 필요한 최소 필드만 담는다.
 */
public record EdgeLookupRow(
        Long edgeId,
        EdgeType edgeType,
        String fromSymbolId,
        String toSymbolId,
        JsonNode toRawRef
) {
}
