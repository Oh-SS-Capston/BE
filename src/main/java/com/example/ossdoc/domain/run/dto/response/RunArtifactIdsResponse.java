package com.example.ossdoc.domain.run.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RunArtifactIdsResponse {

    private Long jobManifestArtifactId;
    private Long buildManifestArtifactId;
    private Long factsArtifactId;
    private Long graphStatsArtifactId;
    /*
     * 대표 라이선스 분석 산출물입니다.
     * 프론트는 이 ID로 license_analysis.json을 조회해서 별도 라이선스 페이지를 구성할 수 있습니다.
     */
    private Long licenseAnalysisArtifactId;

    private Long rankingsArtifactId;
    private Long subsystemsArtifactId;
    private Long classDiagramArtifactId;

    /*
     * Rule 단계 산출물
     */
    private Long ruleCandidatesArtifactId;
    private Long symbolSourceIndexArtifactId;

    /*
     * LLM 단계 산출물
     */
    private Long llmRefinedRulesArtifactId;
    private Long llmScenarioSpecsArtifactId;
    private Long llmSubsystemSummariesArtifactId;
    private Long llmApiDocsArtifactId;
    private Long llmFileTreeDocsArtifactId;
}
