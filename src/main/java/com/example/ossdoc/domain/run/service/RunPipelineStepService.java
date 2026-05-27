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
 * ?④퀎蹂??곹깭 ????쒕퉬?ㅼ엯?덈떎.
 *
 * 媛??④퀎 ?쒖옉/?깃났/?ㅽ뙣/嫄대꼫?? REQUIRES_NEW濡???ν빀?덈떎.
 * 洹몃옒??湲??묒뾽 以묒뿉???꾨줎??polling??吏꾪뻾瑜좎쓣 蹂????덉뒿?덈떎.
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
         * 吏꾪뻾 ?곹깭??repo_run???꾨땲??run_pipeline_job????ν빀?덈떎.
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
        return stepRepository.findStepByJobAndStage(job, stage)
                .orElseGet(() -> stepRepository.save(
                        RunPipelineStepExecution.create(job, run, stage)
                ));
    }
}
