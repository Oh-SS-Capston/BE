package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 추출 단계 메타
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionMeta(
        @JsonProperty("mode")
        String mode,

        @JsonProperty("started_at")
        OffsetDateTime startedAt,

        @JsonProperty("finished_at")
        OffsetDateTime finishedAt,

        @JsonProperty("warnings")
        List<String> warnings
) {
}
