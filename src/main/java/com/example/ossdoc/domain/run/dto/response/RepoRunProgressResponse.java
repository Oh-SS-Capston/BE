package com.example.ossdoc.domain.run.dto.response;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class RepoRunProgressResponse {

    private String runId;
    private String status;
    private String stage;
    private String stageLabel;
    private Integer progress;
    private String message;
    private String failureMessage;
    private String repoUrl;
    private String commitSha;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private RunArtifactIdsResponse artifacts;
    private List<RunStepProgressResponse> steps;
    private List<RunFailedStepResponse> failedSteps;

    public static RepoRunProgressResponse of(
            RepoRun run,
            RunPipelineJob job,
            RunArtifactIdsResponse artifacts,
            List<RunStepProgressResponse> steps,
            List<RunFailedStepResponse> failedSteps
    ) {
        return RepoRunProgressResponse.builder()
                .runId(run.getRunId())
                .status(run.getStatus().name())
                .stage(job.getCurrentStage() == null ? null : job.getCurrentStage().name())
                .stageLabel(job.getCurrentStage() == null ? null : job.getCurrentStage().getDefaultMessage())
                .progress(job.getProgress())
                .message(job.getStatusMessage())
                .failureMessage(job.getFailureMessage())
                .repoUrl(run.getRepoUrl())
                .commitSha(run.getCommitSha())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .artifacts(artifacts)
                .steps(steps)
                .failedSteps(failedSteps)
                .build();
    }
}
