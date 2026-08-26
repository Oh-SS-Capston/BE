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

    /**
     * 실행 중인 job의 lock을 다시 LOCK_MINUTES만큼 연장합니다.
     *
     * 실행 스레드가 아니라 heartbeat 스레드에서 주기적으로 호출합니다.
     * 단계 경계에서만 연장하면 LLM처럼 단계 하나가 lock보다 오래 걸리는 경우를 못 막습니다.
     *
     * @return 연장했으면 true, lock 소유자가 아니거나 실행 중이 아니면 false
     */
    @Transactional
    public boolean renewLock(Long jobId, String workerId) {
        return jobRepository.findById(jobId)
                .map(job -> job.renewLock(workerId, LocalDateTime.now().plusMinutes(LOCK_MINUTES)))
                .orElse(false);
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
