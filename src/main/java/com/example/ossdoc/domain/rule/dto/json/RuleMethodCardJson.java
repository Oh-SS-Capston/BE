package com.example.ossdoc.domain.rule.dto.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
/*
 * RuleMethodCardJson:
 * - rule_candidates.json의 method_cards 섹션 항목.
 * - "메서드 기능 설명"을 위한 최소 단서(method_fqn/source_file/summary_hint)를 담는다.
 */
public record RuleMethodCardJson(
        @JsonProperty("method_fqn")
        String methodFqn,

        @JsonProperty("method_symbol_id")
        String methodSymbolId,

        @JsonProperty("class_fqn")
        String classFqn,

        @JsonProperty("method_name")
        String methodName,

        @JsonProperty("source_file")
        String sourceFile,

        @JsonProperty("start_line")
        Integer startLine,

        @JsonProperty("end_line")
        Integer endLine,

        @JsonProperty("summary_hint")
        String summaryHint,

        @JsonProperty("scenario_hint")
        String scenarioHint,

        @JsonProperty("importance")
        Integer importance,

        @JsonProperty("signal_count")
        Integer signalCount,

        @JsonProperty("signal_types")
        List<String> signalTypes,

        @JsonProperty("avg_confidence_hint")
        BigDecimal avgConfidenceHint
) {
}
