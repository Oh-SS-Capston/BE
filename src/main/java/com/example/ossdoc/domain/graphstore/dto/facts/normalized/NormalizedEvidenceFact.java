package com.example.ossdoc.domain.graphstore.dto.facts.normalized;

public record NormalizedEvidenceFact(
        String id,
        String type,
        String path,
        Integer startLine,
        Integer startCol,
        Integer endLine,
        Integer endCol,
        String symbol,
        String snippet,
        String hash
) {
}