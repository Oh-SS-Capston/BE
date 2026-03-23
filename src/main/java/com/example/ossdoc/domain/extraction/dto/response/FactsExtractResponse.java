package com.example.ossdoc.domain.extraction.dto.response;

import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import lombok.Builder;

import java.util.List;

/**
 * 추출 결과 요약 응답
 */
@Builder
public record FactsExtractResponse(
        String runId,
        ExtractionMode mode,
        BytecodeAvailability bytecodeAvailability,

        String schemaVersion,

        List<String> scannedModules,
        StatsMeta stats,
        List<String> warnings
) {
    public FactsExtractResponse {
        scannedModules = scannedModules == null ? List.of() : List.copyOf(scannedModules);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
