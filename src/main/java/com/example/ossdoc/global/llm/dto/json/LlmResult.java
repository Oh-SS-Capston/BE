package com.example.ossdoc.global.llm.dto.json;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResult {

    private String runId;

    /** 규칙 후보 병합/정규화 결과 -> refined_rules.json */
    private JsonNode refinedRules;

    /** 시나리오 문장화 결과 -> scenario_specs.json */
    private JsonNode scenarioSpecs;

    /** 서브시스템 라벨/요약 결과 -> subsystem_summaries.json */
    private JsonNode subsystemSummaries;

    /** Public API 문서화 결과 -> api_docs.json */
    private JsonNode apiDocs;

    /** 파일 디렉터리 기반 클래스/메서드 설명 -> file_tree_docs.json */
    private JsonNode fileTreeDocs;

    /** 시나리오 캐시 테이블 PK */
    private Long scenarioCacheId;
}
