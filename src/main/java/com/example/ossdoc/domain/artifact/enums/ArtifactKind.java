package com.example.ossdoc.domain.artifact.enums;

public enum ArtifactKind {
    JOB_MANIFEST,
    BUILD_MANIFEST,
    FACTS_JSON,
    GRAPH_STATS,

    // LLM Meaning Refinement 결과물
    LLM_REFINED_RULES,
    LLM_SCENARIO_SPECS,
    LLM_SUBSYSTEM_SUMMARIES,
    LLM_API_DOCS
}
