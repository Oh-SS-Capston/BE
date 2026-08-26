// 역할: 분석 파이프라인에서 생성하는 JSON 산출물 종류를 정의한다.
package com.example.ossdoc.domain.artifact.enums;

public enum ArtifactKind {
    JOB_MANIFEST,
    BUILD_MANIFEST,
    FACTS_JSON,
    GRAPH_STATS,
    LICENSE_ANALYSIS_JSON,

    // Rule Candidate Mining 결과물
    RULE_CANDIDATES_JSON,
    SYMBOL_SOURCE_INDEX_JSON,

    // LLM 정제 결과물
    LLM_REFINED_RULES,
    LLM_SCENARIO_SPECS,
    LLM_SUBSYSTEM_SUMMARIES,
    LLM_API_DOCS,
    LLM_FILE_TREE_DOCS,

    // 그래프 분석/시각화 결과물
    RANKINGS_JSON,
    SUBSYSTEMS_JSON,
    SUPER_SUBSYSTEMS_JSON,
    CLASS_DIAGRAM_JSON,

    // Public API Surface 결과물
    API_SURFACE_JSON,
    API_MAP_JSON,

    // 진입점 탐지 결과물 (B안: ENTRYPOINT 파이프라인 단계)
    ENTRY_POINTS_JSON,

    // API 진입점별 BFS 호출 경로 추적 결과물 (Phase 4-B: LLM step ②/④ 입력)
    API_FLOW_TRACE_JSON
}
