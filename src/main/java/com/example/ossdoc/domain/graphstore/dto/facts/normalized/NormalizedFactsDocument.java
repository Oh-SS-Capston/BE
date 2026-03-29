package com.example.ossdoc.domain.graphstore.dto.facts.normalized;

import java.util.List;
import java.util.Map;

public record NormalizedFactsDocument(
        String schemaVersion,
        Map<String, NormalizedEvidenceFact> evidence,
        List<NormalizedSymbolFact> symbols,
        List<NormalizedRelationFact> relations
) {
}