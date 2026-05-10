package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.PipelineStepStatus;
import com.example.ossdoc.domain.run.enums.RunStage;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "run_pipeline_step_execution",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_pipeline_step_job_stage",
                columnNames = {"job_id", "stage"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunPipelineStepExecution extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_id")
    private Long stepId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private RunPipelineJob job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false)
    private RunStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PipelineStepStatus status;

    @Column(name = "required_step", nullable = false)
    private Boolean requiredStep;

    @Column(name = "progress", nullable = false)
    private Integer progress;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static RunPipelineStepExecution create(
            RunPipelineJob job,
            RepoRun run,
            RunStage stage
    ) {
        RunPipelineStepExecution step = new RunPipelineStepExecution();
        step.job = job;
        step.run = run;
        step.stage = stage;
        step.status = PipelineStepStatus.QUEUED;
        step.requiredStep = stage.isRequired();
        step.progress = stage.getProgress();
        step.message = stage.getDefaultMessage();
        return step;
    }

    public void start(String message) {
        this.status = PipelineStepStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.message = normalizeMessage(message, stage.getDefaultMessage());
        this.errorMessage = null;
    }

    public void succeed(String message) {
        this.status = PipelineStepStatus.SUCCESS;
        this.completedAt = LocalDateTime.now();
        this.message = normalizeMessage(message, stage.getDefaultMessage());
        this.errorMessage = null;
    }

    public void fail(String message) {
        this.status = PipelineStepStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = truncate(message);
    }

    public void skip(String message) {
        this.status = PipelineStepStatus.SKIPPED;
        this.completedAt = LocalDateTime.now();
        this.message = normalizeMessage(message, "단계를 건너뛰었습니다.");
    }

    private String normalizeMessage(String message, String defaultMessage) {
        if (message == null || message.isBlank()) {
            return defaultMessage;
        }

        return message;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        int limit = 4000;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}