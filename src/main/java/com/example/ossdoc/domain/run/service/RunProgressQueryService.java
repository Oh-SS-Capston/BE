package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.dto.response.RepoRunProgressResponse;
import com.example.ossdoc.domain.run.dto.response.RunArtifactIdsResponse;
import com.example.ossdoc.domain.run.dto.response.RunFailedStepResponse;
import com.example.ossdoc.domain.run.dto.response.RunStepProgressResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.enums.PipelineStepStatus;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineStepExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * 프론트 polling용 진행 상태 조회 서비스입니다.
 */
@Service
@RequiredArgsConstructor
public class RunProgressQueryService {

    private final RepoRunRepository repoRunRepository;
    private final RunPipelineJobRepository jobRepository;
    private final RunPipelineStepExecutionRepository stepRepository;
    private final ArtifactRepository artifactRepository;

    @Transactional(readOnly = true)
    public RepoRunProgressResponse getProgress(String runId, Long userId) {
        RepoRun run = repoRunRepository.findByRunIdAndOwner_Id(runId, userId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_FORBIDDEN));

        RunPipelineJob job = jobRepository.findByRun_RunId(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.PIPELINE_JOB_NOT_FOUND));

        var stepEntities = stepRepository.findAllByRun_RunIdOrderByStepIdAsc(runId);

        List<RunStepProgressResponse> steps = stepEntities.stream()
                .map(RunStepProgressResponse::from)
                .toList();

        List<RunFailedStepResponse> failedSteps = stepEntities.stream()
                .filter(step -> step.getStatus() == PipelineStepStatus.FAILED)
                .map(step -> RunFailedStepResponse.builder()
                        .stage(step.getStage().name())
                        .message(step.getErrorMessage())
                        .build())
                .toList();

        RunArtifactIdsResponse artifacts = RunArtifactIdsResponse.builder()
                .jobManifestArtifactId(latestArtifactId(runId, ArtifactKind.JOB_MANIFEST))
                .buildManifestArtifactId(latestArtifactId(runId, ArtifactKind.BUILD_MANIFEST))
                .factsArtifactId(latestArtifactId(runId, ArtifactKind.FACTS_JSON))
                .graphStatsArtifactId(latestArtifactId(runId, ArtifactKind.GRAPH_STATS))

                .rankingsArtifactId(latestArtifactId(runId, ArtifactKind.RANKINGS_JSON))
                .subsystemsArtifactId(latestArtifactId(runId, ArtifactKind.SUBSYSTEMS_JSON))
                .classDiagramArtifactId(latestArtifactId(runId, ArtifactKind.CLASS_DIAGRAM_JSON))

                .ruleCandidatesArtifactId(
                        latestArtifactId(runId, ArtifactKind.RULE_CANDIDATES_JSON)
                )
                .symbolSourceIndexArtifactId(
                        latestArtifactId(runId, ArtifactKind.SYMBOL_SOURCE_INDEX_JSON)
                )

                .llmRefinedRulesArtifactId(
                        latestArtifactId(runId, ArtifactKind.LLM_REFINED_RULES)
                )
                .llmScenarioSpecsArtifactId(
                        latestArtifactId(runId, ArtifactKind.LLM_SCENARIO_SPECS)
                )
                .llmSubsystemSummariesArtifactId(
                        latestArtifactId(runId, ArtifactKind.LLM_SUBSYSTEM_SUMMARIES)
                )
                .llmApiDocsArtifactId(
                        latestArtifactId(runId, ArtifactKind.LLM_API_DOCS)
                )
                .llmFileTreeDocsArtifactId(
                        latestArtifactId(runId, ArtifactKind.LLM_FILE_TREE_DOCS)
                )
                .build();

        return RepoRunProgressResponse.of(
                run,
                job,
                artifacts,
                steps,
                failedSteps
        );
    }

    private Long latestArtifactId(String runId, ArtifactKind kind) {
        return artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, kind)
                .map(Artifact::getArtifactId)
                .orElse(null);
    }
}
