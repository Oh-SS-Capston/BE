package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Graph Builder가 바로 먹을 수 있는 관계 후보 한 건
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationFact(
        @JsonProperty("kind")
        RelationKind kind,

        @JsonProperty("src_symbol")
        String srcSymbol,

        @JsonProperty("dst_symbol")
        String dstSymbol,

        @JsonProperty("dst_raw_ref")
        String dstRawRef,

        @JsonProperty("evidence_ids")
        List<String> evidenceIds,

        @JsonProperty("resolution")
        RelationResolution resolution,

        @JsonProperty("origin")
        FactOriginKind origin,

        @JsonProperty("confidence_hint")
        Double confidenceHint,

        @JsonProperty("attrs")
        Map<String, Object> attrs
) {
    public RelationFact {
        boolean hasDstSymbol = dstSymbol != null && !dstSymbol.isBlank();
        boolean hasDstRaw = dstRawRef != null && !dstRawRef.isBlank();

        if (!hasDstSymbol && !hasDstRaw) {
            throw new IllegalArgumentException("Either dstSymbol or dstRawRef must be provided.");
        }
        if (hasDstSymbol && hasDstRaw) {
            throw new IllegalArgumentException("Only one of dstSymbol or dstRawRef should be provided.");
        }
    }
}