package com.example.ossdoc.domain.graphstore.model.normalized;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

public record NormalizedObservationFact(
        String kind,
        String siteSymbol,
        String targetSymbol,
        JsonNode targetTypeRef,
        String note,
        BigDecimal confidenceHint,
        JsonNode attrs,
        List<String> evidenceIds
) {
}
