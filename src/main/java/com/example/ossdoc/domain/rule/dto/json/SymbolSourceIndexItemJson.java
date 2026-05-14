package com.example.ossdoc.domain.rule.dto.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
/*
 * SymbolSourceIndexItemJson:
 * - symbol_source_index.json의 단일 심볼 앵커 정보.
 * - LLM이 심볼 설명 시 "어디 코드인지" 추적할 수 있는 최소 필드를 담는다.
 */
public record SymbolSourceIndexItemJson(
        @JsonProperty("symbol_id")
        String symbolId,

        @JsonProperty("fqn")
        String fqn,

        @JsonProperty("symbol_kind")
        String symbolKind,

        @JsonProperty("owner_type_fqn")
        String ownerTypeFqn,

        @JsonProperty("source_file")
        String sourceFile,

        @JsonProperty("start_line")
        Integer startLine,

        @JsonProperty("end_line")
        Integer endLine,

        @JsonProperty("evidence_ids")
        List<Long> evidenceIds,

        @JsonProperty("confidence")
        BigDecimal confidence
) {
}
