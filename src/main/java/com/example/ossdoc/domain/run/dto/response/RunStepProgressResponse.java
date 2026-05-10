package com.example.ossdoc.domain.run.dto.response;

import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RunStepProgressResponse {

    private String stage;
    private String status;
    private Boolean required;
    private Integer progress;
    private String message;
    private String errorMessage;

    public static RunStepProgressResponse from(RunPipelineStepExecution step) {
        return RunStepProgressResponse.builder()
                .stage(step.getStage().name())
                .status(step.getStatus().name())
                .required(step.getRequiredStep())
                .progress(step.getProgress())
                .message(step.getMessage())
                .errorMessage(step.getErrorMessage())
                .build();
    }
}