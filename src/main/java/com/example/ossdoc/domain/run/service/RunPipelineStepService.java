package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.enums.RunStage;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineStepExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 단계별 상태 저장 서비스입니다.
 *
 * 각 단계 시작/성공/실패/건너뜀은 REQUIRES_NEW로 저장합니다.
 * 그래서 긴 작업 중에도 프론트 polling이 진행률을 볼 수 있습니다.
 */
@Service
@RequiredArgsConstructor
public class RunPipelineStepService {

    private final RunPipelineJobRepository jobRepository;
    private final RunPipelineStepExecutionRepository stepRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startStep(Long jobId, RunStage stage, String message) {
        RunPipelineJob job = findJob(jobId);
        RepoRun run = job.getRun();

        RunPipelineStepExecution step = findOrCreateStep(job, run, stage);
        step.start(message);

        /*
         * 진행 상태는 repo_run이 아니라 run_pipeline_job에 저장합니다.
         */
        job.updateProgress(stage, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedStep(Long jobId, RunStage stage, String message) {
        RunPipelineJob job = findJob(jobId);

        RunPipelineStepExecution step = findOrCreateStep(job, job.getRun(), stage);
        step.succeed(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(Long jobId, RunStage stage, String message) {
        RunPipelineJob job = findJob(jobId);

        RunPipelineStepExecution step = findOrCreateStep(job, job.getRun(), stage);
        step.fail(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void skipStep(Long jobId, RunStage stage, String message) {
        RunPipelineJob job = findJob(jobId);

        RunPipelineStepExecution step = findOrCreateStep(job, job.getRun(), stage);
        step.skip(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunSuccess(Long jobId) {
        RunPipelineJob job = findJob(jobId);
        job.markSuccess();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunPartialSuccess(Long jobId, String message) {
        RunPipelineJob job = findJob(jobId);
        job.markPartialSuccess(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunFailed(Long jobId, String userMessage, String internalError) {
        RunPipelineJob job = findJob(jobId);
        job.markFailed(userMessage, internalError);
    }

    private RunPipelineJob findJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new RunException(RunErrorCode.PIPELINE_JOB_NOT_FOUND));
    }

    private RunPipelineStepExecution findOrCreateStep(
            RunPipelineJob job,
            RepoRun run,
            RunStage stage
    ) {
        return stepRepository.findByJobAndStage(job, stage)
                .orElseGet(() -> stepRepository.save(
                        RunPipelineStepExecution.create(job, run, stage)
                ));
    }
}