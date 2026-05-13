package com.example.ossdoc.domain.extraction.dto.response;

import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import lombok.Builder;

import java.util.List;

/**
 * 추출 결과 요약 응답
 */
@Builder
public record FactsExtractResponse(
        String runId,
        String mode,

        String schemaVersion,

        StatsMeta stats,
        List<String> warnings
) {
    public FactsExtractResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
