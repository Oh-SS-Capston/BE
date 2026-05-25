package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/*
 * ?뚯씠?꾨씪??Job ??愿由??쒕퉬?? */
@Service
@RequiredArgsConstructor
public class RunPipelineQueueService {

    private static final int LOCK_MINUTES = 30;

    private final RunPipelineJobRepository jobRepository;

    @Transactional
    public RunPipelineJob enqueue(RepoRun run, Long userId) {
        if (jobRepository.existsByRunId(run.getRunId())) {
            throw new RunException(RunErrorCode.PIPELINE_ALREADY_EXISTS);
        }

        return jobRepository.save(RunPipelineJob.create(run, userId));
    }

    @Transactional
    public Optional<RunPipelineJob> claimNextJob(String workerId) {
        Optional<RunPipelineJob> candidate = jobRepository.findNextRunnableJobForUpdate();

        candidate.ifPresent(job -> {
            LocalDateTime now = LocalDateTime.now();
            job.claim(workerId, now, now.plusMinutes(LOCK_MINUTES));
        });

        return candidate;
    }
}
