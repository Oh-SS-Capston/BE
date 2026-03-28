package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.EvidenceKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Map;

/**
 * 코드 위치 기반 근거
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceFact(
        @JsonProperty("id")
        String id,

        @JsonProperty("type")
        EvidenceKind type,

        /**
         * repo 상대경로 권장
         */
        @JsonProperty("path")
        String path,

        @JsonProperty("span")
        SourceSpan span,

        /**
         * 관련 심볼이 있으면 연결
         */
        @JsonProperty("symbol")
        String symbol,

        @JsonProperty("snippet")
        String snippet,

        @JsonProperty("hash")
        String hash,

        @JsonProperty("attrs")
        Map<String, Object> attrs
) {
}