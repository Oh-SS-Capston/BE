package com.example.ossdoc.domain.run.entity;

import com.example.ossdoc.domain.run.enums.PipelineJobStatus;
import com.example.ossdoc.domain.run.enums.RunStage;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "run_pipeline_job",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_run_pipeline_job_run",
                columnNames = "run_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunPipelineJob extends BaseAuditedEntity {

    private static final int USER_MESSAGE_LIMIT = 1000;
    private static final int INTERNAL_ERROR_LIMIT = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PipelineJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage")
    private RunStage currentStage;

    @Column(name = "progress")
    private Integer progress;

    @Column(name = "status_message", length = 500)
    private String statusMessage;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    public static RunPipelineJob create(RepoRun run, Long userId) {
        RunPipelineJob job = new RunPipelineJob();
        job.run = run;
        job.userId = userId;
        job.status = PipelineJobStatus.QUEUED;
        job.currentStage = RunStage.QUEUED;
        job.progress = 0;
        job.statusMessage = RunStage.QUEUED.getDefaultMessage();
        job.attemptCount = 0;
        job.maxAttempts = 1;
        job.nextRunAt = LocalDateTime.now();
        return job;
    }

    public void claim(String workerId, LocalDateTime now, LocalDateTime lockedUntil) {
        this.status = PipelineJobStatus.RUNNING;
        this.lockedBy = workerId;
        this.lockedAt = now;
        this.lockedUntil = lockedUntil;
        this.startedAt = this.startedAt == null ? now : this.startedAt;
        this.attemptCount = this.attemptCount + 1;

        this.run.markRunning();
    }

    public void updateProgress(RunStage stage, String message) {
        this.status = PipelineJobStatus.RUNNING;
        this.currentStage = stage;
        this.progress = stage.getProgress();
        this.statusMessage = normalizeMessage(message, stage.getDefaultMessage(), 500);
        this.failureMessage = null;

        this.run.markRunning();
    }

    public void markSuccess() {
        this.status = PipelineJobStatus.SUCCESS;
        this.currentStage = RunStage.DONE;
        this.progress = 100;
        this.statusMessage = RunStage.DONE.getDefaultMessage();
        this.failureMessage = null;
        this.lastError = null;
        this.completedAt = LocalDateTime.now();

        this.run.markSuccess();
        clearLock();
    }

    public void markPartialSuccess(String message) {
        this.status = PipelineJobStatus.PARTIAL_SUCCESS;
        this.currentStage = RunStage.DONE;
        this.progress = 100;
        this.statusMessage = "일부 분석 결과 생성에 실패했습니다.";
        this.failureMessage = normalizeMessage(message, "일부 선택 단계가 실패했습니다.", USER_MESSAGE_LIMIT);
        this.lastError = this.failureMessage;
        this.completedAt = LocalDateTime.now();

        this.run.markPartialSuccess();
        clearLock();
    }

    public void markFailed(String userMessage, String internalError) {
        this.status = PipelineJobStatus.FAILED;
        this.currentStage = RunStage.FAILED;
        this.progress = 100;
        this.statusMessage = "분석에 실패했습니다.";
        this.failureMessage = normalizeMessage(userMessage, "분석 파이프라인 실행 중 오류가 발생했습니다.", USER_MESSAGE_LIMIT);
        this.lastError = normalizeMessage(internalError, this.failureMessage, INTERNAL_ERROR_LIMIT);
        this.completedAt = LocalDateTime.now();

        this.run.markFailed();
        clearLock();
    }

    public void markRetrying(String errorMessage, LocalDateTime nextRunAt) {
        this.status = PipelineJobStatus.RETRYING;
        this.lastError = normalizeMessage(errorMessage, "재시도 대기 중입니다.", INTERNAL_ERROR_LIMIT);
        this.nextRunAt = nextRunAt;
        clearLock();
    }

    /**
     * 캐시 대기 전용 상태로 전환합니다.
     *
     * 왜 필요한가:
     * - 동일 커밋을 다른 요청이 이미 분석 중일 때 중복 실행을 막고 결과를 기다립니다.
     * - lock 필드를 비워 worker claim SQL 조건(RUNNING + locked_until < now)에서 제외되게 합니다.
     */
    public void markCacheWaiting(String message) {
        this.status = PipelineJobStatus.RUNNING;
        this.currentStage = RunStage.QUEUED;
        this.progress = RunStage.QUEUED.getProgress();
        this.statusMessage = normalizeMessage(message, "동일 분석 결과를 대기 중입니다.", 500);
        this.failureMessage = null;
        this.lastError = null;
        this.nextRunAt = LocalDateTime.now();
        clearLock();

        this.run.markRunning();
    }

    /**
     * 캐시 대기를 종료하고 독립 분석 큐(RETRYING)로 1회 재진입시킵니다.
     *
     * 완화 모드 정책:
     * - 원본 FULL 분석이 실패/미발행이면 대기 run이 직접 분석을 한 번 수행합니다.
     */
    public void scheduleRetryFromCacheWait(String message) {
        this.status = PipelineJobStatus.RETRYING;
        this.currentStage = RunStage.QUEUED;
        this.progress = RunStage.QUEUED.getProgress();
        this.statusMessage = normalizeMessage(message, "캐시 대기에서 독립 분석으로 전환했습니다.", 500);
        this.failureMessage = null;
        this.lastError = this.statusMessage;
        this.nextRunAt = LocalDateTime.now();
        clearLock();

        this.run.markRunning();
    }

    private void clearLock() {
        this.lockedBy = null;
        this.lockedAt = null;
        this.lockedUntil = null;
    }

    private String normalizeMessage(String message, String defaultMessage, int limit) {
        String value = (message == null || message.isBlank()) ? defaultMessage : message;

        if (value.length() <= limit) {
            return value;
        }

        return value.substring(0, limit);
    }
}
