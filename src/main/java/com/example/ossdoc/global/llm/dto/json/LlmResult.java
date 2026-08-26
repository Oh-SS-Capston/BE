package com.example.ossdoc.global.llm.dto.json;

import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResult {

    private String runId;

    private Object refinedRules;

    private Object scenarioSpecs;

    private Object subsystemSummaries;

    private Object apiDocs;

    private Object fileTreeDocs;

    private Long scenarioCacheId;
}
