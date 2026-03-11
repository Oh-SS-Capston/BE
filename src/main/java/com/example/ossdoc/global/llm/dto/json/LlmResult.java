package com.example.ossdoc.global.llm.dto.json;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

@Getter @Builder
@NoArgsConstructor @AllArgsConstructor
public class LlmResult {

    private String runId;

    /** 규칙 후보 병합 / 이름 / 방어 vs 도메인 분류 → refined_rules.json */
    private JsonNode refinedRules;

    /** 시나리오 템플릿 문장화 (각 단계별 근거 링크 포함) → scenario_specs.json */
    private JsonNode scenarioSpecs;

    /** 서브시스템 라벨링 (짧은 이름 + 간략 설명) → subsystem_summaries.json */
    private JsonNode subsystemSummaries;

    /** Public API 문서화 → api_docs.json */
    private JsonNode apiDocs;
}