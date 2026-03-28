package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 추출 단계 메타
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionMeta(
        @JsonProperty("mode")
        ExtractionMode mode,

        @JsonProperty("bytecode_availability")
        BytecodeAvailability bytecodeAvailability,

        @JsonProperty("started_at")
        OffsetDateTime startedAt,

        @JsonProperty("finished_at")
        OffsetDateTime finishedAt,

        @JsonProperty("warnings")
        List<String> warnings,

        @JsonProperty("scanned_modules")
        List<String> scannedModules,

        @JsonProperty("scanned_source_roots")
        List<String> scannedSourceRoots,

        @JsonProperty("scanned_bytecode_roots")
        List<String> scannedBytecodeRoots,

        /**
         * 예: {"ast":"javaparser-3.28.0", "bytecode":"asm-9.7"}
         */
        @JsonProperty("engine_versions")
        Map<String, String> engineVersions
) {
}
