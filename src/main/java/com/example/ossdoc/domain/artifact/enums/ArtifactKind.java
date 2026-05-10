// 역할: 분석 파이프라인에서 생성되는 JSON 산출물 종류를 정의한다.
package com.example.ossdoc.domain.artifact.enums;

public enum ArtifactKind {
    JOB_MANIFEST,
    BUILD_MANIFEST,
    FACTS_JSON,
    GRAPH_STATS,

    // Rule Candidate Mining 결과물
    RULE_CANDIDATES_JSON,

    // LLM 의미 정제 결과물
    LLM_REFINED_RULES,
    LLM_SCENARIO_SPECS,
    LLM_SUBSYSTEM_SUMMARIES,
    LLM_API_DOCS,

    // 그래프 분석/맵 시각화 결과물
    RANKINGS_JSON,
    SUBSYSTEMS_JSON,
    CLASS_DIAGRAM_JSON
}
