package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * DI/SPI/Reflection/Event 같은 비정적 연결의 관측 사실
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationFact(
        @JsonProperty("kind")
        ObservationKind kind,

        /**
         * 관측된 위치의 심볼
         */
        @JsonProperty("site_symbol")
        String siteSymbol,

        /**
         * 대상이 내부 심볼로 해석되면 채움
         */
        @JsonProperty("target_symbol")
        String targetSymbol,

        /**
         * 대상이 타입 참조로만 존재하는 경우
         */
        @JsonProperty("target_type_ref")
        TypeRef targetTypeRef,

        @JsonProperty("note")
        String note,

        @JsonProperty("evidence_ids")
        List<String> evidenceIds,

        @JsonProperty("origin")
        FactOriginKind origin,

        @JsonProperty("confidence_hint")
        Double confidenceHint,

        @JsonProperty("attrs")
        Map<String, Object> attrs
) {
}
