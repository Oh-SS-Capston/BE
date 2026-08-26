package com.example.ossdoc.domain.run.worker;

import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.cache.service.RunCacheWaitResolveService;
import com.example.ossdoc.domain.run.service.RunPipelineExecutor;
import com.example.ossdoc.domain.run.service.RunPipelineQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

/*
 * DB 기반 파이프라인 worker입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPipelineWorker {

    private final RunPipelineQueueService queueService;
    private final RunPipelineExecutor executor;
    private final RunCacheWaitResolveService cacheWaitResolveService;
    private final RunPipelineJobLockHeartbeat lockHeartbeat;

    @Value("${ossdoc.pipeline.worker.max-jobs-per-tick:1}")
    private int maxJobsPerTick;

    @Value("${ossdoc.pipeline.worker.cache-wait-resolve-max-per-tick:5}")
    private int cacheWaitResolveMaxPerTick;

    private final String workerId = buildWorkerId();

    @Scheduled(fixedDelayString = "${ossdoc.pipeline.worker.fixed-delay-ms:2000}")
    public void poll() {
        cacheWaitResolveService.reconcileWaitingRuns(cacheWaitResolveMaxPerTick);

        for (int i = 0; i < maxJobsPerTick; i++) {
            Optional<RunPipelineJob> claimed = queueService.claimNextJob(workerId);

            if (claimed.isEmpty()) {
                return;
            }

            RunPipelineJob job = claimed.get();

            log.info(
                    "[PIPELINE_WORKER] Claimed job. workerId={}, jobId={}, runId={}",
                    workerId,
                    job.getJobId(),
                    job.getRun().getRunId()
            );

            /*
             * lock(30분)보다 오래 걸리는 단계(LLM)가 있으므로 실행 내내 lock을 연장합니다.
             * 연장이 없으면 만료된 lock을 다른 인스턴스가 집어가 같은 job을 중복 실행합니다.
             */
            lockHeartbeat.runWithHeartbeat(
                    job.getJobId(),
                    workerId,
                    () -> executor.execute(job.getJobId())
            );
        }
    }

    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
