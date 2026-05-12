package com.example.ossdoc.domain.artifact.enums;

/**
 * 분석 파이프라인에서 생성/저장하는 아티팩트 종류.
 */
public enum ArtifactKind {
    JOB_MANIFEST,
    BUILD_MANIFEST,
    FACTS_JSON,
    GRAPH_STATS,

    // API/규칙/클러스터 분석 산출물
    API_MAP_JSON,
    RULE_CANDIDATES_JSON,
    RANKINGS_JSON,
    SUBSYSTEMS_JSON,
    CLASS_DIAGRAM_JSON,

    // LLM 정제 산출물
    LLM_REFINED_RULES,
    LLM_SCENARIO_SPECS,
    LLM_SUBSYSTEM_SUMMARIES,
    LLM_API_DOCS,
    LLM_FILE_TREE_DOCS
}
